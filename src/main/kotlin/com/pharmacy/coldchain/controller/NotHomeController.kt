package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.domain.ConfirmReturnTempRequest
import com.pharmacy.coldchain.domain.NotHomeArrivalRequest
import com.pharmacy.coldchain.domain.PharmacistOpinionRequest
import com.pharmacy.coldchain.domain.RescheduleFailRequest
import com.pharmacy.coldchain.domain.RescheduleRequest
import com.pharmacy.coldchain.domain.RescheduleReviewRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.WaitingUpdateRequest
import com.pharmacy.coldchain.service.NotHomeService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 患者不在家处理接口：
 * 骑手登记等待/电话/代收点、药师意见与退回箱温确认、患者改约、退回药店任务。
 */
@RestController
class NotHomeController(private val notHome: NotHomeService) {

    // ---------- 骑手：到达登记 / 等待更新 / 放置代收点 ----------

    @PostMapping("/api/orders/{id}/not-home/arrival")
    fun arrival(@PathVariable id: String, @RequestBody req: NotHomeArrivalRequest, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.arrival(id, uid, req) }

    @PostMapping("/api/orders/{id}/not-home/waiting")
    fun waiting(@PathVariable id: String, @RequestBody req: WaitingUpdateRequest, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.waiting(id, uid, req) }

    @PostMapping("/api/orders/{id}/not-home/leave-pickup-point")
    fun leavePickupPoint(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.leaveAtPickupPoint(id, uid) }

    // ---------- 药师：处置意见 / 改约温控重评 / 退回箱温确认 ----------

    @PostMapping("/api/orders/{id}/not-home/opinion")
    fun opinion(@PathVariable id: String, @RequestBody req: PharmacistOpinionRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> notHome.pharmacistOpinion(id, req, uid) }

    @PostMapping("/api/orders/{id}/reschedule/review")
    fun reviewReschedule(@PathVariable id: String, @RequestBody req: RescheduleReviewRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> notHome.reviewReschedule(id, req, uid) }

    @PostMapping("/api/orders/{id}/not-home/return/confirm-temp")
    fun confirmReturnTemp(@PathVariable id: String, @RequestBody req: ConfirmReturnTempRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> notHome.confirmReturnTemp(id, req, uid) }

    // ---------- 患者/客服/骑手：改约 ----------

    @PostMapping("/api/orders/{id}/reschedule")
    fun requestReschedule(@PathVariable id: String, @RequestBody req: RescheduleRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PATIENT, Role.CUSTOMER_SERVICE, Role.RIDER)
            .let { (uid, role) -> notHome.requestReschedule(id, req, uid, role) }

    @PostMapping("/api/orders/{id}/reschedule/fail")
    fun failReschedule(@PathVariable id: String, @RequestBody req: RescheduleFailRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PATIENT, Role.CUSTOMER_SERVICE, Role.RIDER)
            .let { (uid, role) -> notHome.failReschedule(id, req, uid, role) }

    // ---------- 骑手：按退回任务带回 / 到店 ----------

    @PostMapping("/api/orders/{id}/not-home/return/bring-back")
    fun bringBack(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.bringBack(id, uid) }

    @PostMapping("/api/orders/{id}/not-home/return/arrive-pharmacy")
    fun arrivePharmacy(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER, Role.WAREHOUSE, Role.CUSTOMER_SERVICE)
            .let { (uid, role) -> notHome.arrivePharmacy(id, uid, role) }

    // ---------- 视图 ----------

    /** 患者不在家处理进展：服务记录、改约、退回任务、保温箱剩余可用时长 */
    @GetMapping("/api/orders/{id}/not-home")
    fun caseView(@PathVariable id: String, request: HttpServletRequest): Any {
        val (uid, role) = Auth.require(
            request,
            Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE, Role.COMPENSATION, Role.RIDER
        )
        val view = notHome.caseView(id)
            ?: return mapOf("orderId" to id, "notHome" to null)
        if (role == Role.RIDER && view.visit?.riderId != uid) {
            throw ForbiddenException("骑手只能查看本人配送订单的不在家处理记录")
        }
        return view
    }

    /** 退回药店任务列表（仓库/药师/客服；骑手仅看本人任务） */
    @GetMapping("/api/return-tasks")
    fun returnTasks(request: HttpServletRequest): Any {
        val (uid, role) = Auth.require(
            request, Role.WAREHOUSE, Role.PHARMACIST, Role.CUSTOMER_SERVICE, Role.COMPENSATION, Role.RIDER
        )
        val all = notHome.listReturnTasks()
        return if (role == Role.RIDER) all.filter { it.riderId == uid } else all
    }
}
