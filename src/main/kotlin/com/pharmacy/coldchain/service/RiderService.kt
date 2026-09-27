package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.domain.TrainingRecord
import com.pharmacy.coldchain.domain.TrainingRequest
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import org.springframework.stereotype.Service

/** 骑手冷链培训：未通过培训的骑手不能取冷藏药品 */
@Service
class RiderService(private val store: JsonStore) {

    fun addTraining(riderId: String, req: TrainingRequest): TrainingRecord {
        val rec = TrainingRecord(
            riderId = riderId,
            course = req.course,
            score = req.score,
            passed = req.passed,
            at = System.currentTimeMillis()
        )
        store.rpushJson("training:$riderId", rec)
        return rec
    }

    fun trainings(riderId: String): List<TrainingRecord> =
        store.lrangeJson("training:$riderId")

    /** 有任意一门通过的培训即视为已培训 */
    fun isTrained(riderId: String): Boolean = trainings(riderId).any { it.passed }
}
