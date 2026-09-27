package com.pharmacy.coldchain.repo

import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.OrderStatus
import org.springframework.stereotype.Repository

@Repository
class OrderRepository(private val store: JsonStore) {

    private fun key(id: String) = "order:$id"

    fun save(o: ColdChainOrder) {
        // 维护状态索引：状态变化时从旧集合移除
        val existing = store.get(key(o.id), ColdChainOrder::class.java)
        if (existing != null && existing.status != o.status) {
            store.srem("idx:status:${existing.status}", o.id)
        }
        store.set(key(o.id), o)
        store.sadd("idx:status:${o.status}", o.id)
        store.sadd("idx:patient:${o.patientId}", o.id)
        store.sadd("idx:all", o.id)
    }

    fun find(id: String): ColdChainOrder? = store.get(key(id), ColdChainOrder::class.java)

    fun require(id: String): ColdChainOrder =
        find(id) ?: throw NotFoundException("订单 $id 不存在")

    fun findByStatus(status: OrderStatus): List<ColdChainOrder> =
        store.smembers("idx:status:$status").mapNotNull { find(it) }.sortedBy { it.createdAt }

    fun findByPatient(patientId: String): List<ColdChainOrder> =
        store.smembers("idx:patient:$patientId").mapNotNull { find(it) }.sortedBy { it.createdAt }

    fun findAll(): List<ColdChainOrder> =
        store.smembers("idx:all").mapNotNull { find(it) }.sortedBy { it.createdAt }
}
