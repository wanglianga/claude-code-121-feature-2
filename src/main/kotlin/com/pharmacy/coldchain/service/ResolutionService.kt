package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.DrugDisposition
import com.pharmacy.coldchain.domain.IncidentStatus
import com.pharmacy.coldchain.domain.IncidentType
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.PrescriptionPhoto
import com.pharmacy.coldchain.domain.Resolution
import com.pharmacy.coldchain.domain.ResolutionRequest
import com.pharmacy.coldchain.domain.ResolutionSuggestion
import com.pharmacy.coldchain.domain.RiderLiability
import com.pharmacy.coldchain.domain.Usability
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import com.pharmacy.coldchain.repo.OrderRepository
import org.springframework.stereotype.Service

/** 温控赔付：最终处理结果决定药品报损、补发、退款、骑手责任和患者用药风险提醒 */
@Service
class ResolutionService(
    private val orders: OrderRepository,
    private val store: JsonStore,
    private val incidents: IncidentService,
    private val payments: PaymentService,
    private val orderService: OrderService
) {

    /** 根据未处理的异常事件自动推导处理建议 */
    fun suggest(orderId: String): ResolutionSuggestion {
        val o = orders.require(orderId)
        val openTypes = incidents.byOrder(orderId)
            .filter { it.status == IncidentStatus.OPEN }
            .map { it.type }
        val scrapped = o.usability == Usability.UNUSABLE_SCRAPPED
        val riderCaused = openTypes.any { it == IncidentType.RIDER_DETOUR_OVERTEMP || it == IncidentType.BOX_OPENED_ABNORMAL }
        val tempIncident = openTypes.any { it == IncidentType.OVER_TEMPERATURE }
        val payment = payments.require(orderId)
        val remaining = payment.totalCents - payment.refundedInsuranceCents - payment.refundedSelfPayCents
        return ResolutionSuggestion(
            orderId = orderId,
            suggestedDrugDisposition = if (scrapped) DrugDisposition.SCRAPPED else DrugDisposition.RETURNED_TO_STOCK,
            suggestedReissue = scrapped,
            suggestedRefundCents = if (scrapped || o.status == OrderStatus.AWAITING_RESOLUTION) remaining else 0,
            suggestedRiderLiability = when {
                riderCaused -> RiderLiability.FULL
                tempIncident -> RiderLiability.PARTIAL
                else -> RiderLiability.NONE
            },
            suggestedRiskReminder = if (o.overTemp || tempIncident)
                "药品配送途中曾出现温度异常，使用前请核对外观并咨询药师" else null,
            openIncidentTypes = openTypes
        )
    }

    fun resolve(orderId: String, req: ResolutionRequest, resolver: String): Resolution {
        val o = orders.require(orderId)
        orderService.requireStatus(o, OrderStatus.AWAITING_RESOLUTION)

        // 药品处置与药师判定必须一致
        if (o.usability == Usability.UNUSABLE_SCRAPPED && req.drugDisposition != DrugDisposition.SCRAPPED) {
            throw BadRequestException("药师已判定药品不可继续使用，处置方式必须为 SCRAPPED（报损）")
        }
        if (o.usability == Usability.USABLE && req.drugDisposition == DrugDisposition.SCRAPPED) {
            throw BadRequestException("药师判定药品仍可使用，不能按报损处理，如需退药请选择 RETURNED_TO_STOCK（退回库存）")
        }
        if (req.refundAmountCents < 0 || req.riderFineCents < 0) {
            throw BadRequestException("退款金额与骑手扣款不能为负")
        }

        var insuranceRefund = 0L
        var selfPayRefund = 0L
        if (req.refundAmountCents > 0) {
            val (_, ins, self) = payments.issueRefund(orderId, req.refundAmountCents)
            insuranceRefund = ins
            selfPayRefund = self
        }

        // 补发：基于原订单生成新订单（同一处方已审核通过，直接进入待出库；补发不再收费）
        var newOrderId: String? = null
        if (req.reissue) {
            val now = System.currentTimeMillis()
            val newOrder = ColdChainOrder(
                id = store.nextId("O"),
                patientId = o.patientId,
                patientName = o.patientName,
                drugType = o.drugType,
                drugName = o.drugName,
                quantity = o.quantity,
                prescriptionNo = o.prescriptionNo,
                totalAmountCents = 0,
                insuranceAmountCents = 0,
                selfPayAmountCents = 0,
                status = OrderStatus.READY_FOR_OUTBOUND,
                prescriptionReviewer = o.prescriptionReviewer,
                pharmacistReviewer = o.pharmacistReviewer,
                reissueOfOrderId = o.id,
                medicationReminders = o.medicationReminders,
                createdAt = now,
                updatedAt = now
            )
            orders.save(newOrder)
            payments.createFor(newOrder)
            // 处方照片随单归档到新订单
            store.lrangeJson<PrescriptionPhoto>("rxphoto:${o.id}").forEach { p ->
                store.rpushJson("rxphoto:${newOrder.id}", p.copy(id = store.nextId("P"), orderId = newOrder.id, at = now))
            }
            o.reissuedOrderId = newOrder.id
            newOrderId = newOrder.id
        }

        req.riskReminder?.takeIf { it.isNotBlank() }?.let { o.riskReminders.add(it) }

        val resolution = Resolution(
            orderId = orderId,
            drugDisposition = req.drugDisposition,
            reissue = req.reissue,
            reissueOrderId = newOrderId,
            refundAmountCents = req.refundAmountCents,
            insuranceRefundCents = insuranceRefund,
            selfPayRefundCents = selfPayRefund,
            riderLiability = req.riderLiability,
            riderFineCents = req.riderFineCents,
            riskReminder = req.riskReminder,
            resolvedBy = resolver,
            createdAt = System.currentTimeMillis()
        )
        store.set("resolution:$orderId", resolution)

        o.status = if (req.reissue) OrderStatus.REISSUED else OrderStatus.CLOSED
        orderService.save(o)
        incidents.resolveAllForOrder(orderId, resolver, "赔付处理完成：${req.drugDisposition}")
        return resolution
    }

    fun get(orderId: String): Resolution? = store.get<Resolution>("resolution:$orderId")
}
