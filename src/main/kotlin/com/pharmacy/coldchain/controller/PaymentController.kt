package com.pharmacy.coldchain.controller

import com.pharmacy.coldchain.common.Auth
import com.pharmacy.coldchain.domain.RefundRequest
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.service.PaymentService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 医保结算：医保/自费拆分与退款 */
@RestController
@RequestMapping("/api/orders")
class PaymentController(private val payments: PaymentService) {

    @GetMapping("/{id}/payment-split")
    fun split(@PathVariable id: String, request: HttpServletRequest): Any {
        Auth.require(request, Role.INSURANCE, Role.COMPENSATION, Role.PHARMACIST, Role.CUSTOMER_SERVICE)
        return payments.require(id)
    }

    /** 医保结算人员发起退款（按医保占比自动拆分退回渠道） */
    @PostMapping("/{id}/refund")
    fun refund(@PathVariable id: String, @RequestBody req: RefundRequest, request: HttpServletRequest): Any {
        Auth.require(request, Role.INSURANCE, Role.COMPENSATION)
        val (split, insuranceRefund, selfPayRefund) = payments.issueRefund(id, req.amountCents)
        return mapOf(
            "orderId" to id,
            "refundedTotalCents" to req.amountCents,
            "insuranceRefundCents" to insuranceRefund,
            "selfPayRefundCents" to selfPayRefund,
            "payment" to split
        )
    }
}
