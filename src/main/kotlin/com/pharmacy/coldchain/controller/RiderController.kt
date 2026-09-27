package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.TrainingRequest
import com.pharmacy.coldchain.service.RiderService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 骑手冷链培训 */
@RestController
@RequestMapping("/api/riders")
class RiderController(private val riders: RiderService) {

    @PostMapping("/{riderId}/trainings")
    fun addTraining(@PathVariable riderId: String, @RequestBody req: TrainingRequest, request: HttpServletRequest) =
        Auth.require(request, Role.ADMIN, Role.CUSTOMER_SERVICE).let { riders.addTraining(riderId, req) }

    @GetMapping("/{riderId}/trainings")
    fun trainings(@PathVariable riderId: String, request: HttpServletRequest): Any {
        val (uid, role) = Auth.require(request, Role.RIDER, Role.ADMIN, Role.CUSTOMER_SERVICE, Role.PHARMACIST)
        if (role == Role.RIDER && uid != riderId) {
            throw com.pharmacy.coldchain.common.ForbiddenException("骑手只能查看本人培训记录")
        }
        return riders.trainings(riderId)
    }
}
