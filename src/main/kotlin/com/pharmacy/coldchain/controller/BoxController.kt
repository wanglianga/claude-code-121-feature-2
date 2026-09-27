package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.RecycleRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.BoxService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 冷链保温箱循环使用 */
@RestController
@RequestMapping("/api/boxes")
class BoxController(private val boxes: BoxService) {

    @GetMapping
    fun all(request: HttpServletRequest): Any {
        Auth.require(request, Role.WAREHOUSE, Role.PHARMACIST, Role.CUSTOMER_SERVICE)
        return boxes.all()
    }

    @GetMapping("/{boxNo}")
    fun one(@PathVariable boxNo: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.WAREHOUSE, Role.PHARMACIST, Role.CUSTOMER_SERVICE)
        return boxes.require(boxNo)
    }

    /** 回收：完好 → READY 继续循环；破损 → RETIRED 报废 */
    @PostMapping("/{boxNo}/recycle")
    fun recycle(@PathVariable boxNo: String, @RequestBody req: RecycleRequest, request: HttpServletRequest) =
        Auth.require(request, Role.WAREHOUSE).let { (uid, _) -> boxes.recycle(boxNo, req, uid) }
}
