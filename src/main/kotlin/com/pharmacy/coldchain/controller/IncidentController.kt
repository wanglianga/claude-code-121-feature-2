package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.IncidentRequest
import com.pharmacy.coldchain.domain.ResolveIncidentRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.IncidentService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 异常事件：各角色都可在同一订单上上报，客服/赔付负责处理 */
@RestController
class IncidentController(private val incidents: IncidentService) {

    @PostMapping("/api/orders/{id}/incidents")
    fun report(@PathVariable id: String, @RequestBody req: IncidentRequest, request: HttpServletRequest) =
        Auth.require(request).let { (uid, role) -> incidents.report(id, req.type, role, uid, req.detail, dedupOpen = false) }

    @GetMapping("/api/orders/{id}/incidents")
    fun list(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request)
        return incidents.byOrder(id)
    }

    @PostMapping("/api/incidents/{id}/resolve")
    fun resolve(@PathVariable id: String, @RequestBody req: ResolveIncidentRequest, request: HttpServletRequest) =
        Auth.require(request, Role.CUSTOMER_SERVICE, Role.COMPENSATION).let { (uid, _) ->
            incidents.resolve(id, uid, req.note)
        }
}
