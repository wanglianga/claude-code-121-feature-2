package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.domain.FollowUp
import com.pharmacy.coldchain.domain.FollowUpRequest
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import com.pharmacy.coldchain.repo.OrderRepository
import org.springframework.stereotype.Service

/** 患者用药回访（签收后进行） */
@Service
class FollowUpService(
    private val orders: OrderRepository,
    private val store: JsonStore,
    private val orderService: OrderService
) {

    fun add(orderId: String, req: FollowUpRequest, staffId: String): FollowUp {
        val o = orders.require(orderId)
        orderService.requireStatus(o, OrderStatus.SIGNED)
        val fu = FollowUp(
            id = store.nextId("F"),
            orderId = orderId,
            staffId = staffId,
            result = req.result,
            note = req.note,
            at = System.currentTimeMillis()
        )
        store.rpushJson("followup:$orderId", fu)
        return fu
    }

    fun list(orderId: String): List<FollowUp> = store.lrangeJson("followup:$orderId")
}
