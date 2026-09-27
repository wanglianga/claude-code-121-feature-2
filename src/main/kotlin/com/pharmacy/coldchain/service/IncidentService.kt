package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.Incident
import com.pharmacy.coldchain.domain.IncidentStatus
import com.pharmacy.coldchain.domain.IncidentType
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import org.springframework.stereotype.Service

/** 异常事件服务：把药师、仓库、骑手、患者、客服、医保、赔付连接到同一订单 */
@Service
class IncidentService(private val store: JsonStore) {

    /**
     * 上报异常。dedupOpen=true 时，同订单同类型且仍未处理的事件不重复创建，
     * 避免持续遥测超温刷屏。
     */
    fun report(
        orderId: String,
        type: IncidentType,
        reporterRole: Role,
        reporterId: String,
        detail: String,
        dedupOpen: Boolean = true
    ): Incident {
        if (dedupOpen) {
            byOrder(orderId).firstOrNull { it.type == type && it.status == IncidentStatus.OPEN }
                ?.let { return it }
        }
        val inc = Incident(
            id = store.nextId("I"),
            orderId = orderId,
            type = type,
            reporterRole = reporterRole.name,
            reporterId = reporterId,
            detail = detail,
            createdAt = System.currentTimeMillis()
        )
        store.set("incident:${inc.id}", inc)
        store.sadd("idx:incident:order:$orderId", inc.id)
        return inc
    }

    fun byOrder(orderId: String): List<Incident> =
        store.smembers("idx:incident:order:$orderId")
            .mapNotNull { store.get<Incident>("incident:$it") }
            .sortedBy { it.createdAt }

    fun find(id: String): Incident? = store.get<Incident>("incident:$id")

    fun require(id: String): Incident =
        find(id) ?: throw NotFoundException("异常事件 $id 不存在")

    fun resolve(id: String, resolver: String, note: String): Incident {
        val inc = require(id)
        inc.status = IncidentStatus.RESOLVED
        inc.resolutionNote = note
        inc.resolvedBy = resolver
        inc.resolvedAt = System.currentTimeMillis()
        store.set("incident:${inc.id}", inc)
        return inc
    }

    fun resolveAllForOrder(orderId: String, resolver: String, note: String) {
        byOrder(orderId).filter { it.status == IncidentStatus.OPEN }.forEach {
            resolve(it.id, resolver, note)
        }
    }
}
