package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.NotFoundException
import com.pharmacy.coldchain.domain.BoxStatus
import com.pharmacy.coldchain.domain.ColdChainBox
import com.pharmacy.coldchain.domain.RecycleRequest
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import org.springframework.stereotype.Service

/** 冷链保温箱循环使用管理 */
@Service
class BoxService(private val store: JsonStore) {

    fun find(boxNo: String): ColdChainBox? = store.get<ColdChainBox>("box:$boxNo")

    fun require(boxNo: String): ColdChainBox =
        find(boxNo) ?: throw NotFoundException("保温箱 $boxNo 不存在")

    fun findOrCreate(boxNo: String): ColdChainBox =
        find(boxNo) ?: ColdChainBox(boxNo = boxNo).also { save(it) }

    fun save(b: ColdChainBox) {
        store.set("box:${b.boxNo}", b)
        store.sadd("idx:boxes", b.boxNo)
    }

    fun all(): List<ColdChainBox> =
        store.smembers("idx:boxes").mapNotNull { find(it) }.sortedBy { it.boxNo }

    /** 回收：完好则回到 READY 继续循环，破损则报废 */
    fun recycle(boxNo: String, req: RecycleRequest, operator: String): ColdChainBox {
        val b = require(boxNo)
        if (b.status != BoxStatus.IN_USE) {
            throw ConflictException("保温箱 $boxNo 当前状态为 ${b.status}，只有配送完成回收中的箱子才能执行回收")
        }
        if (req.conditionOk) {
            b.status = BoxStatus.READY
            b.timesUsed += 1
            b.conditionNote = req.note ?: "回收检查合格（操作人 $operator）"
        } else {
            b.status = BoxStatus.RETIRED
            b.conditionNote = req.note ?: "回收检查不合格，报废（操作人 $operator）"
        }
        b.currentOrderId = null
        save(b)
        return b
    }
}
