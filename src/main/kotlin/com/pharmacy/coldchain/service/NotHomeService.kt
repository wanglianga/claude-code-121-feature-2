package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.domain.CallResult
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.ConfirmReturnTempRequest
import com.pharmacy.coldchain.domain.IncidentType
import com.pharmacy.coldchain.domain.NotHomeArrivalRequest
import com.pharmacy.coldchain.domain.NotHomeCaseView
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.PharmacistOpinion
import com.pharmacy.coldchain.domain.PharmacistOpinionRequest
import com.pharmacy.coldchain.domain.Reschedule
import com.pharmacy.coldchain.domain.RescheduleFailRequest
import com.pharmacy.coldchain.domain.RescheduleRequest
import com.pharmacy.coldchain.domain.RescheduleReviewRequest
import com.pharmacy.coldchain.domain.RescheduleStatus
import com.pharmacy.coldchain.domain.ReturnTask
import com.pharmacy.coldchain.domain.ReturnTaskStatus
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.ServiceVisit
import com.pharmacy.coldchain.domain.Usability
import com.pharmacy.coldchain.domain.WaitingUpdateRequest
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import com.pharmacy.coldchain.repo.OrderRepository
import org.springframework.stereotype.Service

/**
 * 患者不在家处理：
 * 骑手到达无人签收 → 服务记录（等待时间/电话结果/冷藏代收点/箱温）→ 药师意见；
 * 超过保温箱安全时限后禁止直接二次派送，须经药师温控重评（改约）或退回药店；
 * 改约时重新计算保温箱剩余可用时长；药师确认退回箱温前骑手不能带回；改约失败生成退回药店任务。
 */
@Service
class NotHomeService(
    private val orders: OrderRepository,
    private val store: JsonStore,
    private val incidents: IncidentService
) {

    companion object {
        /** 出库后保温箱默认安全配送时限（分钟） */
        const val DEFAULT_SAFETY_LIMIT_MINUTES = 120L
    }

    // ---------- 存储 ----------

    private fun visitKey(orderId: String) = "service-visit:$orderId"
    private fun rescheduleKey(orderId: String) = "reschedule:$orderId"
    private fun returnTaskKey(orderId: String) = "return-task:$orderId"

    fun getVisit(orderId: String): ServiceVisit? = store.get(visitKey(orderId))
    fun getReschedule(orderId: String): Reschedule? = store.get(rescheduleKey(orderId))
    fun getReturnTask(orderId: String): ReturnTask? = store.get(returnTaskKey(orderId))

    private fun requireVisit(orderId: String): ServiceVisit =
        getVisit(orderId) ?: throw ConflictException("订单 $orderId 尚无患者不在家服务记录，请骑手先到达登记")

    private fun requireReturnTask(orderId: String): ReturnTask =
        getReturnTask(orderId) ?: throw ConflictException("订单 $orderId 尚未生成退回药店任务")

    fun listReturnTasks(): List<ReturnTask> =
        store.smembers("idx:return-tasks").mapNotNull { store.get<ReturnTask>("return-task:$it") }
            .sortedBy { it.createdAt }

    // ---------- 骑手：到达登记 / 等待更新 ----------

    /** 骑手到达后无人签收，建立服务记录，订单进入签收失败并等待药师重新判断 */
    fun arrival(orderId: String, riderId: String, req: NotHomeArrivalRequest): ServiceVisit {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.DELIVERING)
        requireAssignedRider(o, riderId)
        if (req.waitedSeconds < 0) throw BadRequestException("等待时长不能为负")
        // 二次配送尝试（改约/继续配送后）再次无人签收：归档上一次服务记录后重建
        getVisit(orderId)?.let {
            store.rpushJson("service-visit-history:$orderId", it)
        }
        getReschedule(orderId)
            ?.takeIf { it.status == RescheduleStatus.APPROVED || it.status == RescheduleStatus.REJECTED }
            ?.let { store.rpushJson("reschedule-history:$orderId", it); store.del(rescheduleKey(orderId)) }

        val now = System.currentTimeMillis()
        val exceeded = safetyExceeded(o, now)
        val visit = ServiceVisit(
            id = store.nextId("SV"),
            orderId = orderId,
            riderId = riderId,
            arrivedAt = now,
            waitedSeconds = req.waitedSeconds,
            callResult = req.callResult,
            callNote = req.callNote,
            pickupPointAvailable = req.pickupPointAvailable,
            pickupPointName = req.pickupPointName,
            boxTempC = req.boxTempC,
            safetyLimitExceeded = exceeded,
            updatedAt = now
        )
        store.set(visitKey(orderId), visit)

        o.status = OrderStatus.SIGN_FAILED
        o.usability = Usability.PENDING_REJUDGMENT
        o.lastDeliveryStatus = "PATIENT_NOT_HOME"
        recordBoxTempIfAbnormal(o, req.boxTempC, riderId)
        save(o)

        incidents.report(
            orderId, IncidentType.PATIENT_NOT_HOME, Role.RIDER, riderId,
            "骑手到达后患者不在家，无人签收；已等待 ${req.waitedSeconds / 60} 分钟，" +
                "电话结果：${req.callResult?.name ?: "未联系"}"
        )
        if (exceeded) reportSafetyIncident(o, riderId)
        return visit
    }

    /** 等待过程中持续补充等待时间、电话结果、代收点信息与实测箱温 */
    fun waiting(orderId: String, riderId: String, req: WaitingUpdateRequest): ServiceVisit {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        requireAssignedRider(o, riderId)
        val visit = requireVisit(orderId)
        if (req.waitedSeconds < visit.waitedSeconds) {
            throw BadRequestException("累计等待时长不能小于上次记录的 ${visit.waitedSeconds} 秒")
        }
        val now = System.currentTimeMillis()
        visit.waitedSeconds = req.waitedSeconds
        req.callResult?.let { visit.callResult = it }
        req.callNote?.let { visit.callNote = it }
        visit.pickupPointAvailable = req.pickupPointAvailable
        req.pickupPointName?.let { visit.pickupPointName = it }
        req.boxTempC?.let { visit.boxTempC = it }
        visit.updatedAt = now

        if (!visit.safetyLimitExceeded && safetyExceeded(o, now)) {
            visit.safetyLimitExceeded = true
        }
        store.set(visitKey(orderId), visit)
        recordBoxTempIfAbnormal(o, req.boxTempC, riderId)
        save(o)
        if (visit.safetyLimitExceeded) reportSafetyIncident(o, riderId)
        return visit
    }

    // ---------- 药师意见 ----------

    /** 药师结合等待时间、电话结果、代收点与箱温给出处置意见 */
    fun pharmacistOpinion(orderId: String, req: PharmacistOpinionRequest, pharmacistId: String): ServiceVisit {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        val visit = requireVisit(orderId)
        getReturnTask(orderId)?.let {
            throw ConflictException("订单已生成退回药店任务（${it.status}），不能再给出其他处置意见")
        }
        getReschedule(orderId)?.takeIf { it.status == RescheduleStatus.PROPOSED }?.let {
            throw ConflictException("已有待评估的改约申请，请先完成改约温控重评")
        }

        val now = System.currentTimeMillis()
        val exceeded = visit.safetyLimitExceeded || safetyExceeded(o, now)
        when (req.opinion) {
            PharmacistOpinion.CONTINUE_DELIVERY -> {
                // 超过安全时限后不能直接二次派送，必须重新评估温控
                if (exceeded) {
                    throw ConflictException("已超过保温箱安全时限，不能直接恢复/二次派送，请安排改约温控重评或退回药店")
                }
                if (o.overTemp) {
                    throw ConflictException("配送途中存在超温记录，不能直接继续派送，请先重新判断药品可用性")
                }
            }
            PharmacistOpinion.LEAVE_AT_PICKUP_POINT -> {
                if (!visit.pickupPointAvailable) {
                    throw ConflictException("骑手服务记录中无可用冷藏代收点，不能给出放置代收点意见")
                }
                if (exceeded) {
                    throw ConflictException("已超过保温箱安全时限，不能放置代收点直接交付，请安排改约温控重评或退回药店")
                }
            }
            PharmacistOpinion.RESCHEDULE -> { /* 等待患者发起改约 */ }
            PharmacistOpinion.RETURN_TO_PHARMACY -> { /* 下方生成退回任务 */ }
        }

        visit.pharmacistOpinion = req.opinion
        visit.pharmacistId = pharmacistId
        visit.pharmacistComment = req.comment
        visit.pharmacistAdvisedAt = now
        visit.updatedAt = now
        store.set(visitKey(orderId), visit)

        when (req.opinion) {
            PharmacistOpinion.CONTINUE_DELIVERY -> {
                o.usability = Usability.USABLE
                o.status = OrderStatus.DELIVERING
                o.lastDeliveryStatus = "CONTINUE_AFTER_WAITING"
                save(o)
                incidents.resolveByTypes(
                    orderId, setOf(IncidentType.PATIENT_NOT_HOME),
                    pharmacistId, "药师意见：等待时间短且温控正常，继续配送"
                )
            }
            PharmacistOpinion.LEAVE_AT_PICKUP_POINT -> {
                // 药师同意后，由骑手执行放置；放置前订单仍挂起
                o.usability = Usability.USABLE
                save(o)
            }
            PharmacistOpinion.RETURN_TO_PHARMACY -> {
                // 药师直接要求退回：若已有改约申请，一并标记失败
                getReschedule(orderId)?.takeIf { it.status == RescheduleStatus.PROPOSED }?.let { r ->
                    r.status = RescheduleStatus.FAILED
                    r.failureReason = "药师意见要求退回药店：${req.comment ?: "未说明"}"
                    r.updatedAt = now
                    store.set(rescheduleKey(orderId), r)
                }
                createReturnTask(o, "药师意见：退回药店（${req.comment ?: "未说明"}）", now)
            }
            else -> save(o)
        }
        return visit
    }

    /** 药师同意后，骑手把药品放置到冷藏代收点，订单恢复配送等待患者签收 */
    fun leaveAtPickupPoint(orderId: String, riderId: String): ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        requireAssignedRider(o, riderId)
        val visit = requireVisit(orderId)
        if (visit.pharmacistOpinion != PharmacistOpinion.LEAVE_AT_PICKUP_POINT) {
            throw ConflictException("药师尚未同意放置冷藏代收点，骑手不能自行放置")
        }
        if (visit.safetyLimitExceeded || safetyExceeded(o, System.currentTimeMillis())) {
            throw ConflictException("已超过保温箱安全时限，不能放置代收点交付，请按退回流程处理")
        }
        o.status = OrderStatus.DELIVERING
        o.usability = Usability.USABLE
        o.lastDeliveryStatus = "LEFT_AT_PICKUP_POINT:${visit.pickupPointName ?: ""}"
        save(o)
        incidents.resolveByTypes(
            orderId, setOf(IncidentType.PATIENT_NOT_HOME),
            riderId, "药品已放置冷藏代收点 ${visit.pickupPointName ?: ""}"
        )
        return o
    }

    // ---------- 患者改约 ----------

    /** 患者同意改约签收：服务立即重新计算保温箱剩余可用时长，随后等待药师温控重评 */
    fun requestReschedule(orderId: String, req: RescheduleRequest, actorId: String, actorRole: Role): Reschedule {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        if (actorRole == Role.PATIENT && o.patientId != actorId) {
            throw ForbiddenException("只能为本人订单申请改约")
        }
        if (actorRole == Role.RIDER) requireAssignedRider(o, actorId)
        getReturnTask(orderId)?.let {
            throw ConflictException("订单已生成退回药店任务，不能再改约")
        }
        getReschedule(orderId)?.takeIf { it.status == RescheduleStatus.PROPOSED || it.status == RescheduleStatus.APPROVED }
            ?.let { throw ConflictException("订单已存在${if (it.status == RescheduleStatus.PROPOSED) "待评估" else "已通过"}的改约申请") }

        val now = System.currentTimeMillis()
        if (req.newAppointmentAt <= now) {
            throw BadRequestException("新预约签收时间必须晚于当前时间")
        }
        val remaining = remainingBoxMinutes(o, now)
        val reschedule = Reschedule(
            id = store.nextId("RS"),
            orderId = orderId,
            requestedBy = actorId,
            newAppointmentAt = req.newAppointmentAt,
            status = RescheduleStatus.PROPOSED,
            remainingBoxMinutes = remaining,
            recalculatedValidUntil = o.boxValidUntil,
            createdAt = now,
            updatedAt = now
        )
        store.set(rescheduleKey(orderId), reschedule)
        if (safetyExceeded(o, now)) reportSafetyIncident(o, actorId)
        return reschedule
    }

    /** 药师对改约进行温控重评：核对超温记录与保温箱剩余可用时长是否覆盖新预约时间 */
    fun reviewReschedule(orderId: String, req: RescheduleReviewRequest, pharmacistId: String): Reschedule {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        val r = getReschedule(orderId)
            ?: throw ConflictException("订单尚无改约申请")
        if (r.status != RescheduleStatus.PROPOSED) {
            throw ConflictException("改约申请状态为 ${r.status}，不能重复评估")
        }

        val now = System.currentTimeMillis()
        val remaining = remainingBoxMinutes(o, now)
        r.remainingBoxMinutes = remaining
        r.recalculatedValidUntil = o.boxValidUntil
        r.pharmacistId = pharmacistId
        r.pharmacistComment = req.comment
        r.reviewedAt = now

        if (req.approved) {
            // 重新评估温控：有超温记录 / 超过安全时限 / 剩余时长覆盖不了新预约 → 不通过
            if (o.overTemp) {
                throw ConflictException("温控重评不通过：配送途中存在超温记录，不能按改约继续派送")
            }
            val validUntil = o.boxValidUntil
                ?: throw ConflictException("缺少保温箱安全时限记录，不能完成温控重评")
            if (now >= validUntil) {
                throw ConflictException("温控重评不通过：已超过保温箱安全时限，不能二次派送")
            }
            if (r.newAppointmentAt > validUntil) {
                throw ConflictException(
                    "温控重评不通过：新预约时间晚于保温箱可用截止时间，剩余约 $remaining 分钟"
                )
            }
            r.status = RescheduleStatus.APPROVED
            store.set(rescheduleKey(orderId), r)

            o.usability = Usability.USABLE
            o.status = OrderStatus.DELIVERING
            o.estimatedDeliveryAt = r.newAppointmentAt
            o.lastDeliveryStatus = "RESCHEDULED"
            save(o)
            incidents.resolveByTypes(
                orderId, setOf(IncidentType.PATIENT_NOT_HOME),
                pharmacistId, "改约温控重评通过，保温箱剩余可用约 $remaining 分钟"
            )
        } else {
            r.status = RescheduleStatus.REJECTED
            store.set(rescheduleKey(orderId), r)
        }
        return r
    }

    /** 改约失败（患者确认无法签收 / 联系不上等）→ 生成退回药店任务 */
    fun failReschedule(orderId: String, req: RescheduleFailRequest, actorId: String, actorRole: Role): ReturnTask {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        if (actorRole == Role.PATIENT && o.patientId != actorId) {
            throw ForbiddenException("只能操作本人订单")
        }
        if (actorRole == Role.RIDER && o.riderId != actorId) {
            throw ForbiddenException("该订单由骑手 ${o.riderId} 配送，$actorId 无权操作")
        }
        if (req.reason.isBlank()) throw BadRequestException("改约失败原因不能为空")
        val r = getReschedule(orderId) ?: throw ConflictException("订单尚无改约申请")
        if (r.status == RescheduleStatus.FAILED) throw ConflictException("改约已标记失败")
        val now = System.currentTimeMillis()
        r.status = RescheduleStatus.FAILED
        r.failureReason = req.reason
        r.updatedAt = now
        store.set(rescheduleKey(orderId), r)

        val task = createReturnTask(o, "改约失败：${req.reason}", now)
        incidents.report(
            orderId, IncidentType.RESCHEDULE_FAILED, actorRole, actorId,
            "改约失败（${r.status}）：${req.reason}，生成退回药店任务 ${task.id}"
        )
        return task
    }

    // ---------- 退回药店任务 ----------

    /** 药师确认退回箱温：骑手把药品带回前的前置条件 */
    fun confirmReturnTemp(orderId: String, req: ConfirmReturnTempRequest, pharmacistId: String): ReturnTask {
        orders.require(orderId)
        val task = requireReturnTask(orderId)
        if (task.status != ReturnTaskStatus.CREATED) {
            throw ConflictException("退回任务状态为 ${task.status}，箱温已确认或已在退回途中")
        }
        val now = System.currentTimeMillis()
        task.confirmedBoxTempC = req.boxTempC
        task.pharmacistId = pharmacistId
        task.pharmacistComment = req.comment
        task.confirmedAt = now
        task.status = ReturnTaskStatus.RETURN_CONFIRMED
        task.updatedAt = now
        store.set(returnTaskKey(orderId), task)
        return task
    }

    /** 药师确认前，骑手不能把药品带回；确认后骑手开始送回药店（不得继续派送） */
    fun bringBack(orderId: String, riderId: String): ReturnTask {
        val o = orders.require(orderId)
        requireAssignedRider(o, riderId)
        val task = requireReturnTask(orderId)
        when (task.status) {
            ReturnTaskStatus.CREATED -> throw ConflictException("药师尚未确认退回箱温，骑手不能把药品带回，也不能继续派送")
            ReturnTaskStatus.IN_TRANSIT, ReturnTaskStatus.ARRIVED ->
                throw ConflictException("退回任务状态为 ${task.status}，不能重复带回")
            ReturnTaskStatus.RETURN_CONFIRMED -> { /* 允许 */ }
        }
        val now = System.currentTimeMillis()
        task.status = ReturnTaskStatus.IN_TRANSIT
        task.pickedUpAt = now
        task.updatedAt = now
        store.set(returnTaskKey(orderId), task)

        o.status = OrderStatus.RETURNING
        o.usability = Usability.PENDING_REJUDGMENT
        o.lastDeliveryStatus = "RETURNING_TO_PHARMACY"
        save(o)
        return task
    }

    /** 骑手把药品送回药店：药品待药师重新判断，保温箱可回收 */
    fun arrivePharmacy(orderId: String, actorId: String, actorRole: Role): ReturnTask {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.RETURNING)
        if (actorRole == Role.RIDER) requireAssignedRider(o, actorId)
        val task = requireReturnTask(orderId)
        if (task.status != ReturnTaskStatus.IN_TRANSIT) {
            throw ConflictException("退回任务状态为 ${task.status}，不能登记到店")
        }
        val now = System.currentTimeMillis()
        task.status = ReturnTaskStatus.ARRIVED
        task.arrivedAt = now
        task.updatedAt = now
        store.set(returnTaskKey(orderId), task)

        o.status = OrderStatus.AWAITING_RESOLUTION
        o.usability = Usability.PENDING_REJUDGMENT
        o.lastDeliveryStatus = "RETURNED_TO_PHARMACY"
        save(o)
        // 不在家协同事件随到店关闭；超温等事件保留给药师/赔付处理
        incidents.resolveByTypes(
            orderId,
            setOf(IncidentType.PATIENT_NOT_HOME, IncidentType.SAFETY_LIMIT_EXCEEDED, IncidentType.RESCHEDULE_FAILED),
            actorId, "药品已退回药店，等待药师重新判断与处置"
        )
        return task
    }

    // ---------- 视图 ----------

    /** 患者不在家完整处理视图 */
    fun caseView(orderId: String): NotHomeCaseView? {
        val o = orders.find(orderId) ?: return null
        val visit = getVisit(orderId)
        val reschedule = getReschedule(orderId)
        val task = getReturnTask(orderId)
        if (visit == null && reschedule == null && task == null) return null
        val now = System.currentTimeMillis()
        val remaining = remainingBoxMinutes(o, now)
        val exceeded = visit?.safetyLimitExceeded ?: safetyExceeded(o, now)
        return NotHomeCaseView(
            visit = visit,
            reschedule = reschedule,
            returnTask = task,
            boxValidUntil = o.boxValidUntil,
            remainingBoxMinutes = remaining,
            safetyLimitMinutes = o.safetyLimitMinutes,
            safetyLimitExceeded = exceeded,
            canRedeliver = o.status == OrderStatus.DELIVERING && o.usability == Usability.USABLE,
            visitHistory = store.lrangeJson("service-visit-history:$orderId"),
            rescheduleHistory = store.lrangeJson("reschedule-history:$orderId")
        )
    }

    // ---------- 工具 ----------

    private fun createReturnTask(o: ColdChainOrder, reason: String, now: Long): ReturnTask {
        getReturnTask(o.id)?.let { return it }
        val task = ReturnTask(
            id = store.nextId("RT"),
            orderId = o.id,
            riderId = o.riderId ?: throw ConflictException("订单尚无配送骑手，不能生成退回任务"),
            status = ReturnTaskStatus.CREATED,
            reason = reason,
            createdAt = now,
            updatedAt = now
        )
        store.set(returnTaskKey(o.id), task)
        store.sadd("idx:return-tasks", o.id)
        return task
    }

    private fun reportSafetyIncident(o: ColdChainOrder, reporterId: String) {
        incidents.report(
            o.id, IncidentType.SAFETY_LIMIT_EXCEEDED, Role.RIDER, reporterId,
            "患者不在家滞留已超过保温箱安全时限 ${o.safetyLimitMinutes} 分钟，" +
                "禁止直接二次派送，须药师重新评估温控（改约或退回）"
        )
    }

    /** 骑手上报的箱温超出冷链范围时记录超温（不改变已挂起的签收失败状态） */
    private fun recordBoxTempIfAbnormal(o: ColdChainOrder, boxTempC: Double?, reporterId: String) {
        if (boxTempC == null) return
        val range = o.drugType
        if (boxTempC < range.minTempC || boxTempC > range.maxTempC) {
            o.overTemp = true
            incidents.report(
                o.id, IncidentType.OVER_TEMPERATURE, Role.RIDER, reporterId,
                "患者门口实测箱温 ${boxTempC}°C 超出冷链范围 ${range.minTempC}-${range.maxTempC}°C"
            )
        }
    }

    private fun remainingBoxMinutes(o: ColdChainOrder, now: Long): Long? =
        o.boxValidUntil?.let { (it - now + 59_999) / 60_000 }

    private fun safetyExceeded(o: ColdChainOrder, now: Long): Boolean =
        o.boxValidUntil?.let { now > it } ?: false

    private fun requireAssignedRider(o: ColdChainOrder, riderId: String) {
        if (o.riderId != riderId) {
            throw ForbiddenException("该订单由骑手 ${o.riderId} 配送，$riderId 无权操作")
        }
    }

    private fun requireStatus(o: ColdChainOrder, vararg allowed: OrderStatus) {
        if (o.status !in allowed) {
            throw ConflictException("订单 ${o.id} 当前状态为 ${o.status}，不允许该操作（需要: ${allowed.joinToString()}）")
        }
    }

    private fun save(o: ColdChainOrder): ColdChainOrder {
        o.updatedAt = System.currentTimeMillis()
        orders.save(o)
        return o
    }
}
