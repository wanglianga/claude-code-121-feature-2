package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.BoxDurationRecalc
import com.pharmacy.coldchain.domain.BringBackRequest
import com.pharmacy.coldchain.domain.CoolPointAvailability
import com.pharmacy.coldchain.domain.IncidentType
import com.pharmacy.coldchain.domain.NotHomeArrivalRequest
import com.pharmacy.coldchain.domain.NotHomePharmacistDecisionRequest
import com.pharmacy.coldchain.domain.NotHomeServiceRecord
import com.pharmacy.coldchain.domain.NotHomeStage
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.PharmacistOpinion
import com.pharmacy.coldchain.domain.PhoneResult
import com.pharmacy.coldchain.domain.RescheduleRequest
import com.pharmacy.coldchain.domain.ReturnTask
import com.pharmacy.coldchain.domain.ReturnTaskStatus
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.Usability
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.OrderRepository
import com.pharmacy.coldchain.repo.get
import org.springframework.stereotype.Service

/**
 * 患者不在家处理：
 * 骑手到达无人签收 → 服务记录（等待时间/电话结果/冷藏代收点/药师意见）→
 * 安全时限内患者同意改约则重算保温箱可用时长后二次派送；
 * 超过安全时限或改约失败 → 生成退回药店任务，药师授权后骑手方可带回 →
 * 药店接收 → 药师确认退回箱温、重新评估温控（可用→重新出库；不可用→报损赔付）。
 */
@Service
class NotHomeService(
    private val orders: OrderRepository,
    private val store: JsonStore,
    private val incidents: IncidentService
) {

    companion object {
        /** 每块冰袋额定保温 60 分钟，出库未指定时按冰袋数量折算 */
        const val MINUTES_PER_ICE_PACK = 60
        const val DEFAULT_MIN_RATED_MINUTES = 120

        fun ratedMinutes(icePackCount: Int, explicit: Int?, minRatedMinutes: Int = DEFAULT_MIN_RATED_MINUTES): Int =
            explicit?.takeIf { it >= 1 }
                ?: maxOf(minRatedMinutes, icePackCount * MINUTES_PER_ICE_PACK)
    }

    // ---------- 存储 ----------

    private fun recordKey(orderId: String) = "nothome:$orderId"

    fun findRecord(orderId: String): NotHomeServiceRecord? = store.get(recordKey(orderId))

    fun requireRecord(orderId: String): NotHomeServiceRecord =
        findRecord(orderId) ?: throw NotFoundException("订单 $orderId 没有患者不在家服务记录")

    private fun saveRecord(rec: NotHomeServiceRecord): NotHomeServiceRecord {
        rec.updatedAt = System.currentTimeMillis()
        store.set(recordKey(rec.orderId), rec)
        return rec
    }

    private fun taskKey(id: String) = "task:return:$id"

    fun findTask(id: String): ReturnTask? = store.get(taskKey(id))

    fun requireTask(id: String): ReturnTask =
        findTask(id) ?: throw NotFoundException("退回药店任务 $id 不存在")

    fun findTaskByOrder(orderId: String): ReturnTask? =
        findRecord(orderId)?.returnTaskId?.let { findTask(it) }

    private fun saveTask(t: ReturnTask) {
        t.updatedAt = System.currentTimeMillis()
        store.set(taskKey(t.id), t)
        store.sadd("idx:tasks:return", t.id)
        store.sadd("idx:tasks:return:rider:${t.riderId}", t.id)
    }

    fun listTasks(riderId: String? = null): List<ReturnTask> {
        val ids = riderId?.let { store.smembers("idx:tasks:return:rider:$it") }
            ?: store.smembers("idx:tasks:return")
        return ids.mapNotNull { findTask(it) }.sortedBy { it.createdAt }
    }

    // ---------- 1. 骑手到达无人签收 ----------

    /** 骑手到达后无人签收：登记等待时间、电话结果、是否可放置冷藏代收点，等待药师意见 */
    fun arriveNotHome(orderId: String, riderId: String, req: NotHomeArrivalRequest): NotHomeServiceRecord {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.DELIVERING, OrderStatus.RESCHEDULED)
        if (o.riderId != riderId) throw ForbiddenException("该订单由骑手 ${o.riderId} 配送，$riderId 无权操作")
        if (req.waitedMinutes < 0) throw BadRequestException("等待时长不能为负")
        findRecord(orderId)?.let { existing ->
            if (existing.stage != NotHomeStage.CLOSED) {
                // 改约后二次派送再次无人签收：改约机会已用尽，直接生成退回药店任务
                if (existing.stage == NotHomeStage.RESCHEDULED) {
                    return secondAttemptFailed(existing, o, riderId, req)
                }
                throw ConflictException("订单已存在患者不在家服务记录（阶段 ${existing.stage}），请按改约/退回流程继续处理")
            }
        }

        val now = System.currentTimeMillis()
        val rec = NotHomeServiceRecord(
            id = store.nextId("NH"),
            orderId = orderId,
            riderId = riderId,
            stage = NotHomeStage.AWAITING_DECISION,
            arrivedAt = now - req.waitedMinutes * 60_000L,
            waitedMinutes = req.waitedMinutes,
            phoneResult = req.phoneResult,
            phoneNote = req.phoneNote,
            coolPoint = req.coolPoint,
            coolPointNote = req.coolPointNote,
            createdAt = now,
            updatedAt = now
        )
        saveRecord(rec)

        o.status = OrderStatus.SIGN_FAILED
        o.usability = Usability.PENDING_REJUDGMENT
        o.lastDeliveryStatus = "PATIENT_NOT_HOME"
        save(o)

        incidents.report(
            orderId, IncidentType.PATIENT_NOT_HOME, Role.RIDER, riderId,
            "骑手到达无人签收：等待 ${req.waitedMinutes} 分钟，电话结果=${req.phoneResult}，" +
                "冷藏代收点=${req.coolPoint}，等待药师处置意见"
        )
        return rec
    }

    /** 改约后二次派送仍无人签收：改约机会已用尽，直接生成退回药店任务 */
    private fun secondAttemptFailed(
        rec: NotHomeServiceRecord,
        o: com.pharmacy.coldchain.domain.ColdChainOrder,
        riderId: String,
        req: NotHomeArrivalRequest
    ): NotHomeServiceRecord {
        val now = System.currentTimeMillis()
        rec.waitedMinutes += req.waitedMinutes
        rec.phoneResult = req.phoneResult
        req.phoneNote?.let { rec.phoneNote = (rec.phoneNote?.let { p -> "$p；" } ?: "") + "二次派送：$it" }
        rec.coolPoint = req.coolPoint
        req.coolPointNote?.let { rec.coolPointNote = it }
        rec.pharmacistOpinion = PharmacistOpinion.BRING_BACK
        rec.pharmacistComment = "改约后二次派送仍无人签收，系统直接生成退回药店任务"
        rec.pharmacistDecidedAt = now
        rec.safeLimitExceededAtDecision = now > (o.boxSafeUntil ?: now)
        saveRecord(rec)
        o.status = OrderStatus.SIGN_FAILED
        o.usability = Usability.PENDING_REJUDGMENT
        createBringBackTask(rec, o, now, rec.safeLimitExceededAtDecision == true,
            "改约后二次派送仍无人签收（等待 ${req.waitedMinutes} 分钟，电话=${req.phoneResult}）")
        incidents.report(
            rec.orderId, IncidentType.PATIENT_NOT_HOME_RETURNED, Role.RIDER, riderId,
            "改约后二次派送仍无人签收，不再二次派送，退回药店任务 ${rec.returnTaskId}"
        )
        return rec
    }

    // ---------- 2. 药师处置意见 ----------

    /**
     * 药师给出处置意见：
     *  - WAIT_AND_RESCHEDULE：仅当仍在保温箱安全时限内才允许，等待患者改约；
     *  - BRING_BACK：要求骑手带回药店重新评估温控，并生成退回药店任务。
     */
    fun pharmacistDecision(orderId: String, pharmacistId: String, req: NotHomePharmacistDecisionRequest): NotHomeServiceRecord {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        val rec = requireRecord(orderId)
        if (rec.stage != NotHomeStage.AWAITING_DECISION) {
            throw ConflictException("服务记录已进入 ${rec.stage} 阶段，不能再给出处置意见")
        }

        val now = System.currentTimeMillis()
        val safeUntil = o.boxSafeUntil
            ?: throw ConflictException("订单缺少保温箱安全时限数据，不能改约，应带回药店重新评估")
        val exceeded = now > safeUntil
        rec.pharmacistId = pharmacistId
        rec.pharmacistComment = req.comment
        rec.pharmacistDecidedAt = now
        rec.safeLimitExceededAtDecision = exceeded

        if (req.opinion == PharmacistOpinion.WAIT_AND_RESCHEDULE) {
            if (o.overTemp) {
                rec.pharmacistOpinion = PharmacistOpinion.BRING_BACK
                saveRecord(rec)
                createBringBackTask(rec, o, now, exceeded,
                    "配送途中已有超温记录，温控无法保证，不能直接二次派送")
                throw ConflictException(
                    "配送途中已出现超温记录，订单不能直接二次派送，" +
                        "已生成退回药店任务，须带回药店并由药师确认退回箱温后重新评估温控"
                )
            }
            if (exceeded) {
                rec.pharmacistOpinion = PharmacistOpinion.BRING_BACK
                saveRecord(rec)
                createBringBackTask(rec, o, now, exceeded,
                    "药师处置时已超过保温箱安全时限，不能直接二次派送")
                throw ConflictException(
                    "已超过保温箱安全时限（$safeUntil），订单不能直接二次派送，" +
                        "已生成退回药店任务，须带回药店并由药师确认退回箱温后重新评估温控"
                )
            }
            rec.pharmacistOpinion = PharmacistOpinion.WAIT_AND_RESCHEDULE
            o.lastDeliveryStatus = "WAIT_FOR_RESCHEDULE"
            save(o)
            return saveRecord(rec)
        }

        // BRING_BACK：授权骑手带回 + 生成退回药店任务
        rec.pharmacistOpinion = PharmacistOpinion.BRING_BACK
        saveRecord(rec)
        createBringBackTask(rec, o, now, exceeded, req.comment ?: "药师要求带回药店重新评估温控")
        return rec
    }

    private fun createBringBackTask(rec: NotHomeServiceRecord, o: com.pharmacy.coldchain.domain.ColdChainOrder, now: Long, exceeded: Boolean, extra: String): ReturnTask {
        rec.stage = NotHomeStage.BRING_BACK
        o.lastDeliveryStatus = "BRING_BACK_APPROVED"
        save(o)

        val task = ReturnTask(
            id = store.nextId("RT"),
            orderId = rec.orderId,
            riderId = rec.riderId,
            status = ReturnTaskStatus.CREATED,
            reason = buildReturnReason(rec, exceeded, extra),
            createdAt = now,
            updatedAt = now
        )
        saveTask(task)
        rec.returnTaskId = task.id
        saveRecord(rec)
        return task
    }

    private fun buildReturnReason(rec: NotHomeServiceRecord, exceeded: Boolean, extra: String): String {
        val parts = mutableListOf("患者不在家改约失败")
        if (exceeded) parts.add("已超过保温箱安全时限")
        when (rec.phoneResult) {
            PhoneResult.NO_ANSWER -> parts.add("电话无人接听")
            PhoneResult.ANSWERED_REJECT -> parts.add("患者拒绝改约/无法签收")
            PhoneResult.INVALID_CONTACT -> parts.add("联系方式无效")
            PhoneResult.ANSWERED_AGREE_RESCHEDULE -> {}
        }
        if (rec.coolPoint != CoolPointAvailability.AVAILABLE) {
            parts.add("无法放置冷藏代收点（${rec.coolPoint}）")
        }
        parts.add(extra)
        return parts.joinToString("，")
    }

    // ---------- 3. 患者同意改约：重算保温箱可用时长 ----------

    /**
     * 患者电话同意改约签收：按剩余保温时长与改约后预计在途时长重新计算。
     * 剩余时长不足 → 超出安全时限，不能二次派送，自动生成退回药店任务重新评估温控。
     * 骑手本人或客服均可代为登记。
     */
    fun reschedule(orderId: String, operatorId: String, operatorRole: Role, req: RescheduleRequest): NotHomeServiceRecord {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        val rec = requireRecord(orderId)
        if (operatorRole == Role.RIDER && o.riderId != operatorId) {
            throw ForbiddenException("该订单由骑手 ${o.riderId} 配送，$operatorId 无权操作")
        }
        if (operatorRole != Role.RIDER && operatorRole != Role.CUSTOMER_SERVICE) {
            throw ForbiddenException("只有配送骑手或客服可以登记患者改约")
        }
        if (rec.stage != NotHomeStage.AWAITING_DECISION) {
            throw ConflictException("当前阶段 ${rec.stage} 不能改约")
        }
        if (rec.pharmacistOpinion != PharmacistOpinion.WAIT_AND_RESCHEDULE) {
            throw ConflictException("药师尚未给出“等待改约”意见（当前意见=${rec.pharmacistOpinion}），不能直接改约二次派送")
        }
        if (req.etaMinutes < 1) throw BadRequestException("改约后预计在途时长必须 ≥ 1 分钟")

        val now = System.currentTimeMillis()
        val safeUntil = o.boxSafeUntil!!
        val remainingMinutes = ((safeUntil - now) / 60_000L).toInt()

        rec.rescheduleAgreed = true
        rec.rescheduleAgreedAt = now
        rec.rescheduleEtaMinutes = req.etaMinutes
        rec.rescheduleNote = req.note

        if (now >= safeUntil || remainingMinutes < req.etaMinutes) {
            // 超过安全时限：订单不能直接二次派送，改约失败 → 生成退回药店任务
            rec.boxRecalc = BoxDurationRecalc.EXCEEDED_SAFE_LIMIT
            rec.boxRemainingMinutesAfterReset = remainingMinutes.coerceAtLeast(0)
            rec.boxSafeUntilAfterReset = null
            rec.pharmacistOpinion = PharmacistOpinion.BRING_BACK // 超时自动转为药师要求带回
            rec.pharmacistComment = (rec.pharmacistComment?.let { "$it；" } ?: "") +
                "改约重算时已超安全时限，系统转退回药店"
            saveRecord(rec)
            val task = createBringBackTask(
                rec, o, now, true,
                "改约重算保温时长：剩余约 ${remainingMinutes.coerceAtLeast(0)} 分钟，" +
                    "不足以覆盖改约后在途 ${req.etaMinutes} 分钟"
            )
            incidents.report(
                orderId, IncidentType.PATIENT_NOT_HOME_RETURNED,
                if (operatorRole == Role.RIDER) Role.RIDER else Role.CUSTOMER_SERVICE, operatorId,
                "患者虽同意改约，但保温箱剩余安全时长不足，超过安全时限不能二次派送，" +
                    "已生成退回药店任务 ${task.id}"
            )
            throw ConflictException(
                "保温箱剩余安全时长约 ${remainingMinutes.coerceAtLeast(0)} 分钟，" +
                    "不足以覆盖改约后在途 ${req.etaMinutes} 分钟：超过安全时限，订单不能直接二次派送，" +
                    "已生成退回药店任务 ${task.id}，须带回重新评估温控"
            )
        }

        // 重算保温箱可用时长：以当前时刻为基准，授予改约航段所需保温窗口（不超过剩余额定能力）
        val newSafeUntil = now + req.etaMinutes * 60_000L
        o.boxDurationResetAt = now
        o.boxRemainingMinutesAtReset = remainingMinutes
        o.boxSafeUntil = newSafeUntil
        o.estimatedDeliveryAt = newSafeUntil
        o.status = OrderStatus.RESCHEDULED
        o.usability = Usability.USABLE // 安全时限内 + 药师同意等待改约 + 时长足够
        o.lastDeliveryStatus = "RESCHEDULED"
        save(o)

        rec.boxRecalc = BoxDurationRecalc.WITHIN_SAFE_LIMIT
        rec.boxSafeUntilAfterReset = newSafeUntil
        rec.boxRemainingMinutesAfterReset = remainingMinutes
        rec.stage = NotHomeStage.RESCHEDULED
        saveRecord(rec)
        incidents.resolveAllForOrder(
            orderId, rec.pharmacistId ?: "pharmacist",
            "患者同意改约，保温箱剩余时长 $remainingMinutes 分钟，重算安全截止至 $newSafeUntil"
        )
        return rec
    }

    /** 改约完成后骑手开始二次派送：必须仍在重算后的安全时限内，且途中未出现超温 */
    fun resumeDelivery(orderId: String, riderId: String): com.pharmacy.coldchain.domain.ColdChainOrder {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.RESCHEDULED)
        if (o.riderId != riderId) throw ForbiddenException("该订单由骑手 ${o.riderId} 配送，$riderId 无权操作")
        if (o.overTemp) {
            throw ConflictException("等待改约期间出现超温记录，不能二次派送，须药师重新评估温控")
        }
        val safeUntil = o.boxSafeUntil
        if (safeUntil == null || System.currentTimeMillis() >= safeUntil) {
            throw ConflictException("已超过重算后的保温箱安全时限，不能二次派送，须退回药店重新评估温控")
        }
        o.status = OrderStatus.DELIVERING
        o.lastDeliveryStatus = "SECOND_ATTEMPT"
        return save(o)
    }

    // ---------- 4. 骑手带回（须药师授权） ----------

    /** 骑手开始执行退回药店任务：CREATED → IN_TRANSIT */
    fun startReturn(orderId: String, riderId: String): ReturnTask {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        val rec = requireRecord(orderId)
        if (rec.riderId != riderId) throw ForbiddenException("退回任务由骑手 ${rec.riderId} 执行")
        if (rec.pharmacistOpinion != PharmacistOpinion.BRING_BACK || rec.returnTaskId == null) {
            // 药师确认前，骑手端不能直接把药品带回继续派送
            throw ConflictException("药师确认前，骑手不能自行把药品带回；请先等待药师处置意见")
        }
        val task = requireTask(rec.returnTaskId!!)
        if (task.status == ReturnTaskStatus.CREATED) {
            task.status = ReturnTaskStatus.IN_TRANSIT
            rec.bringBackInitiatedAt = System.currentTimeMillis()
            saveRecord(rec)
            saveTask(task)
            o.lastDeliveryStatus = "RETURNING"
            save(o)
        }
        return task
    }

    /** 骑手把药品带回药店并交接：上报交接箱温，订单进入退回药店待温控重估；任务仍待药店确认接收 */
    fun bringBack(orderId: String, riderId: String, req: BringBackRequest): ReturnTask {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.SIGN_FAILED)
        val rec = requireRecord(orderId)
        if (rec.riderId != riderId) throw ForbiddenException("退回任务由骑手 ${rec.riderId} 执行")
        if (rec.pharmacistOpinion != PharmacistOpinion.BRING_BACK || rec.returnTaskId == null) {
            throw ConflictException("药师确认前，骑手不能自行把药品带回；请先等待药师处置意见")
        }
        val task = requireTask(rec.returnTaskId!!)
        if (task.status == ReturnTaskStatus.RECEIVED) throw ConflictException("药品已退回药店并完成交接")
        if (task.status == ReturnTaskStatus.CREATED) {
            task.status = ReturnTaskStatus.IN_TRANSIT
            rec.bringBackInitiatedAt = rec.bringBackInitiatedAt ?: System.currentTimeMillis()
        }

        val now = System.currentTimeMillis()
        task.boxTempC = req.boxTempC
        task.receiveNote = req.note
        saveTask(task)

        rec.returnedToPharmacyAt = now
        rec.stage = NotHomeStage.RETURNED
        saveRecord(rec)

        o.status = OrderStatus.RETURNED_TO_PHARMACY
        o.usability = Usability.PENDING_REJUDGMENT
        o.lastTempC = req.boxTempC
        o.lastDeliveryStatus = "RETURNED_TO_PHARMACY"
        save(o)
        return task
    }

    /** 药店侧（仓库/药师）确认接收退回药品 */
    fun receiveTask(taskId: String, operator: String, note: String?): ReturnTask {
        val task = requireTask(taskId)
        if (task.status == ReturnTaskStatus.CANCELLED) throw ConflictException("任务已取消")
        task.status = ReturnTaskStatus.RECEIVED
        task.receivedBy = operator
        task.receivedAt = task.receivedAt ?: System.currentTimeMillis()
        if (note != null) task.receiveNote = note
        saveTask(task)

        val rec = findRecord(task.orderId)
        if (rec != null && rec.stage == NotHomeStage.BRING_BACK) {
            rec.returnedToPharmacyAt = task.receivedAt
            rec.stage = NotHomeStage.RETURNED
            saveRecord(rec)
        }
        val o = orders.find(task.orderId)
        if (o != null && o.status == OrderStatus.SIGN_FAILED) {
            o.status = OrderStatus.RETURNED_TO_PHARMACY
            o.usability = Usability.PENDING_REJUDGMENT
            o.lastDeliveryStatus = "RETURNED_TO_PHARMACY"
            save(o)
        }
        return task
    }

    // ---------- 5. 药师确认退回箱温 ----------

    /** 药师确认退回箱温（重新评估温控的前提），随后须通过 rejudgment 给出药品可用性结论 */
    fun confirmReturnTemp(orderId: String, pharmacistId: String, boxTempC: Double, comment: String?): NotHomeServiceRecord {
        val o = orders.require(orderId)
        requireStatus(o, OrderStatus.RETURNED_TO_PHARMACY)
        val rec = requireRecord(orderId)
        if (rec.stage != NotHomeStage.RETURNED && rec.stage != NotHomeStage.REJUDGED) {
            throw ConflictException("药品尚未退回药店（当前阶段 ${rec.stage}），不能确认退回箱温")
        }
        val range = o.drugType
        if (boxTempC < range.minTempC || boxTempC > range.maxTempC) {
            // 仅记录事实，不直接下结论：是否可用由药师在重新判断时综合判定
            rec.returnTempComment = "退回箱温 ${boxTempC}°C 超出冷链范围 ${range.minTempC}-${range.maxTempC}°C；" + (comment ?: "")
        }
        rec.returnBoxTempC = boxTempC
        rec.returnTempConfirmedBy = pharmacistId
        rec.returnTempConfirmedAt = System.currentTimeMillis()
        if (boxTempC in range.minTempC..range.maxTempC) rec.returnTempComment = comment
        o.lastTempC = boxTempC
        save(o)
        incidents.report(
            orderId, IncidentType.PATIENT_NOT_HOME_RETURNED, Role.PHARMACIST, pharmacistId,
            "药师确认退回箱温 ${boxTempC}°C，待重新评估温控" + (comment?.let { "：$it" } ?: "")
        )
        return saveRecord(rec)
    }

    /** 药师完成温控重新评估后，由 OrderService.rejudgment 回调 */
    fun markRejudged(orderId: String) {
        findRecord(orderId)?.let {
            it.stage = NotHomeStage.REJUDGED
            saveRecord(it)
        }
    }

    /** 订单签收/结案后关闭服务记录 */
    fun closeRecord(orderId: String) {
        findRecord(orderId)?.let {
            if (it.stage != NotHomeStage.CLOSED) {
                it.stage = NotHomeStage.CLOSED
                saveRecord(it)
            }
        }
    }

    // ---------- 内部工具 ----------

    private fun save(o: com.pharmacy.coldchain.domain.ColdChainOrder): com.pharmacy.coldchain.domain.ColdChainOrder {
        o.updatedAt = System.currentTimeMillis()
        orders.save(o)
        return o
    }

    private fun requireStatus(o: com.pharmacy.coldchain.domain.ColdChainOrder, vararg allowed: OrderStatus) {
        if (o.status !in allowed) {
            throw ConflictException("订单 ${o.id} 当前状态为 ${o.status}，不允许该操作（需要: ${allowed.joinToString()}）")
        }
    }
}
