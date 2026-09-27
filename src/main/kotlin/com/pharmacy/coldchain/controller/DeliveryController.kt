package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.DeliveryAttemptRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.TelemetryRequest
import com.pharmacy.coldchain.service.DeliveryService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 骑手配送接口 */
@RestController
@RequestMapping("/api/orders")
class DeliveryController(private val delivery: DeliveryService) {

    @PostMapping("/{id}/pickup")
    fun pickup(@PathVariable id: String, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> delivery.pickup(id, uid) }

    @PostMapping("/{id}/telemetry")
    fun telemetry(@PathVariable id: String, @RequestBody req: TelemetryRequest, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> delivery.telemetry(id, uid, req) }

    @GetMapping("/{id}/telemetry")
    fun telemetryList(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.PHARMACIST, Role.WAREHOUSE, Role.CUSTOMER_SERVICE, Role.COMPENSATION, Role.RIDER)
        return delivery.telemetryList(id)
    }

    @PostMapping("/{id}/delivery-attempt")
    fun deliveryAttempt(@PathVariable id: String, @RequestBody req: DeliveryAttemptRequest, request: HttpServletRequest) =
        Auth.require(request, Role.RIDER).let { (uid, _) -> delivery.deliveryAttempt(id, uid, req) }
}
