package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.PaymentSplit
import com.pharmacy.coldchain.domain.RefundStatus
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import org.springframework.stereotype.Service

/** 医保/自费拆分与退款 */
@Service
class PaymentService(private val store: JsonStore) {

    fun createFor(order: ColdChainOrder): PaymentSplit {
        val split = PaymentSplit(
            orderId = order.id,
            totalCents = order.totalAmountCents,
            insuranceCents = order.insuranceAmountCents,
            selfPayCents = order.selfPayAmountCents
        )
        store.set("payment:${order.id}", split)
        return split
    }

    fun get(orderId: String): PaymentSplit? = store.get<PaymentSplit>("payment:$orderId")

    fun require(orderId: String): PaymentSplit =
        get(orderId) ?: throw NotFoundException("订单 $orderId 的支付拆分不存在")

    /**
     * 退款：按医保占比自动拆分为医保退回 + 自费退回。
     * 返回三元组：更新后的拆分、本次医保退款、本次自费退款。
     */
    fun issueRefund(orderId: String, amountCents: Long): Triple<PaymentSplit, Long, Long> {
        val p = require(orderId)
        val remaining = p.totalCents - p.refundedInsuranceCents - p.refundedSelfPayCents
        if (amountCents <= 0) throw BadRequestException("退款金额必须大于 0")
        if (amountCents > remaining) throw BadRequestException("退款金额 $amountCents 超过剩余可退金额 $remaining（分）")

        val insuranceRemaining = p.insuranceCents - p.refundedInsuranceCents
        val selfPayRemaining = p.selfPayCents - p.refundedSelfPayCents

        var insuranceRefund = if (p.totalCents > 0)
            Math.round(amountCents * (p.insuranceCents.toDouble() / p.totalCents.toDouble()))
        else 0L
        insuranceRefund = insuranceRefund.coerceIn(0L, insuranceRemaining)
        val selfPayRefund = amountCents - insuranceRefund
        if (selfPayRefund > selfPayRemaining) {
            throw BadRequestException("自费部分剩余可退 $selfPayRemaining 分，不足以按比例拆分，请调整退款金额")
        }

        p.refundedInsuranceCents += insuranceRefund
        p.refundedSelfPayCents += selfPayRefund
        val refundedTotal = p.refundedInsuranceCents + p.refundedSelfPayCents
        p.refundStatus = when {
            refundedTotal <= 0 -> RefundStatus.NONE
            refundedTotal >= p.totalCents -> RefundStatus.FULL
            else -> RefundStatus.PARTIAL
        }
        store.set("payment:$orderId", p)
        return Triple(p, insuranceRefund, selfPayRefund)
    }
}
