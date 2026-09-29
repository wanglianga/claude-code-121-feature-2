package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.domain.BringBackRequest
import com.pharmacy.coldchain.domain.NotHomeArrivalRequest
import com.pharmacy.coldchain.domain.NotHomePharmacistDecisionRequest
import com.pharmacy.coldchain.domain.RescheduleRequest
import com.pharmacy.coldchain.domain.ReturnTempConfirmRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.NotHomeService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 患者不在家处理接口：
 * 骑手登记到达信息 → 药师处置意见 → 患者改约（重算保温箱时长）或退回药店（确认退回箱温后重估温控）
 */
@RestController
@RequestMapping("/api/orders/{id}/not-home")
class NotHomeController(private val notHome: NotHomeService) {

    /** 骑手到达无人签收：等待时间、电话结果、是否可放置冷藏代收点 */
    @PostMapping("/arrive")
    fun arrive(@PathVariable id: String, @RequestBody req: NotHomeArrivalRequest, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.arriveNotHome(id, uid, req) }

    /** 药师处置意见：安全时限内等待改约，或要求带回药店重新评估温控 */
    @PostMapping("/decision")
    fun decision(@PathVariable id: String, @RequestBody req: NotHomePharmacistDecisionRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) -> notHome.pharmacistDecision(id, uid, req) }

    /** 患者同意改约签收：骑手或客服登记，服务重新计算保温箱可用时长 */
    @PostMapping("/reschedule")
    fun reschedule(@PathVariable id: String, @RequestBody req: RescheduleRequest, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER, Role.CUSTOMER_SERVICE).let { (uid, role) ->
            notHome.reschedule(id, uid, role, req)
        }

    /** 改约完成后骑手开始二次派送 */
    @PostMapping("/resume")
    fun resume(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.resumeDelivery(id, uid) }

    /** 骑手开始执行退回药店任务（须药师已要求带回） */
    @PostMapping("/return/start")
    fun startReturn(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.startReturn(id, uid) }

    /** 骑手把药品带回药店交接，上报交接箱温（药师确认前禁止自行带回） */
    @PostMapping("/return/handover")
    fun handover(@PathVariable id: String, @RequestBody req: BringBackRequest, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> notHome.bringBack(id, uid, req) }

    /** 药师确认退回箱温（重新评估温控的前提） */
    @PostMapping("/return-temp")
    fun confirmReturnTemp(@PathVariable id: String, @RequestBody req: ReturnTempConfirmRequest, request: HttpServletRequest) =
        Auth.require(request, Role.PHARMACIST).let { (uid, _) ->
            notHome.confirmReturnTemp(id, uid, req.boxTempC, req.comment)
        }

    /** 查看不在家服务记录：本单骑手或药房各角色 */
    @GetMapping
    fun view(@PathVariable id: String, request: HttpServletRequest): Any {
        val (uid, role) = Auth.require(
            request,
            Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE,
            Role.COMPENSATION, Role.INSURANCE, Role.RIDER
        )
        val rec = notHome.requireRecord(id)
        if (role == Role.RIDER && rec.riderId != uid) {
            throw ForbiddenException("骑手只能查看本人配送订单的不在家记录")
        }
        return rec
    }
}
