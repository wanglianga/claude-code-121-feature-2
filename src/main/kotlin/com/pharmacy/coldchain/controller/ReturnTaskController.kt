package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.domain.ReturnTaskReceiveRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.NotHomeService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 退回药店任务：改约失败/超安全时限后生成，骑手执行带回，药店确认接收 */
@RestController
class ReturnTaskController(private val notHome: NotHomeService) {

    /** 退回任务列表：骑手只看本人任务，药店/客服/药师/赔付可看全部并可按骑手过滤 */
    @GetMapping("/api/return-tasks")
    fun list(@RequestParam(required = false) riderId: String?, request: HttpServletRequest): Any {
        val (uid, role) = Auth.require(
            request,
            Role.RIDER, Role.WAREHOUSE, Role.PHARMACIST, Role.CUSTOMER_SERVICE, Role.COMPENSATION
        )
        return if (role == Role.RIDER) {
            notHome.listTasks(uid)
        } else {
            notHome.listTasks(riderId)
        }
    }

    @GetMapping("/api/return-tasks/{taskId}")
    fun detail(@PathVariable taskId: String, request: HttpServletRequest): Any {
        val (uid, role) = Auth.require(
            request,
            Role.RIDER, Role.WAREHOUSE, Role.PHARMACIST, Role.CUSTOMER_SERVICE, Role.COMPENSATION
        )
        val task = notHome.requireTask(taskId)
        if (role == Role.RIDER && task.riderId != uid) {
            throw ForbiddenException("骑手只能查看本人的退回任务")
        }
        return task
    }

    /** 药店（仓库/药师）确认接收退回药品 */
    @PostMapping("/api/return-tasks/{taskId}/receive")
    fun receive(
        @PathVariable taskId: String,
        @RequestBody(required = false) req: ReturnTaskReceiveRequest?,
        request: HttpServletRequest
    ) = Auth.require(request, Role.WAREHOUSE, Role.PHARMACIST).let { (uid, _) ->
        notHome.receiveTask(taskId, uid, req?.note)
    }
}
