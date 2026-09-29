package com.pharmacy.coldchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * 端到端流程测试：使用真实 Redis（database 1），每个用例前清空。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.data.redis.database=1"]
)
class ColdChainFlowTests {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var om: ObjectMapper

    @BeforeEach
    fun clean() {
        redis.execute(RedisCallback<Void> { conn -> conn.serverCommands().flushDb(); null })
    }

    // ---------- 测试工具 ----------

    private fun headers(role: String, user: String) = HttpHeaders().apply {
        set("X-Role", role)
        set("X-User-Id", user)
        contentType = MediaType.APPLICATION_JSON
    }

    private fun post(url: String, body: Any?, role: String, user: String): ResponseEntity<JsonNode> =
        rest.exchange(url, HttpMethod.POST, HttpEntity(body?.let { om.writeValueAsString(it) }, headers(role, user)), JsonNode::class.java)

    private fun get(url: String, role: String, user: String): ResponseEntity<JsonNode> =
        rest.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers(role, user)), JsonNode::class.java)

    private fun createOrder(patientId: String = "patient-1", total: Long = 100000, insurance: Long = 70000): String {
        val resp = post("/api/orders", mapOf(
            "patientId" to patientId, "patientName" to "张三",
            "drugType" to "INSULIN", "drugName" to "门冬胰岛素注射液", "quantity" to 2,
            "prescriptionNo" to "RX-2026-0001",
            "totalAmountCents" to total, "insuranceAmountCents" to insurance
        ), "PHARMACIST", "pharmacist-1")
        assertThat(resp.statusCode.value()).isEqualTo(200)
        return resp.body!!.get("id").asText()
    }

    private fun trainRider(riderId: String = "rider-1") {
        val resp = post("/api/riders/$riderId/trainings",
            mapOf("course" to "COLD_CHAIN_BASICS", "score" to 95, "passed" to true), "ADMIN", "admin-1")
        assertThat(resp.statusCode.value()).isEqualTo(200)
    }

    /** 推进到配送中：照片归档 → 处方审核 → 药师复核 → 出库 → 骑手取药 */
    private fun toDelivering(
        orderId: String,
        boxNo: String = "BOX-001",
        riderId: String = "rider-1",
        safetyLimitMinutes: Long? = null
    ) {
        assertThat(post("/api/orders/$orderId/prescription-photos",
            mapOf("url" to "https://img.example.com/rx/$orderId.jpg"), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/prescription-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/pharmacist-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-2").statusCode.value()).isEqualTo(200)
        val outbound = mutableMapOf(
            "batchNo" to "B20260901", "expiryDate" to "2027-06-30", "boxNo" to boxNo,
            "icePackCount" to 2, "outboundTempC" to 5.0, "etaMinutes" to 45
        )
        safetyLimitMinutes?.let { outbound["safetyLimitMinutes"] = it }
        assertThat(post("/api/orders/$orderId/outbound", outbound, "WAREHOUSE", "warehouse-1").statusCode.value()).isEqualTo(200)
        trainRider(riderId)
        assertThat(post("/api/orders/$orderId/pickup", null, "RIDER", riderId).statusCode.value()).isEqualTo(200)
    }

    private fun signOk(orderId: String, patientId: String = "patient-1"): ResponseEntity<JsonNode> =
        post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to true, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", patientId)

    // ---------- 用例 ----------

    @Test
    fun `正常流程：下单到签收回访与保温箱回收`() {
        val orderId = createOrder()

        // 未归档处方照片时不能审核
        val earlyReview = post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        assertThat(earlyReview.statusCode.value()).isEqualTo(409)

        toDelivering(orderId)

        // 出库信息完整
        val view = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!
        val order = view.get("order")
        assertThat(order.get("status").asText()).isEqualTo("DELIVERING")
        assertThat(order.get("batchNo").asText()).isEqualTo("B20260901")
        assertThat(order.get("boxNo").asText()).isEqualTo("BOX-001")
        assertThat(order.get("icePackCount").asInt()).isEqualTo(2)
        assertThat(order.get("outboundTempC").asDouble()).isEqualTo(5.0)
        assertThat(order.get("estimatedDeliveryAt").asLong()).isGreaterThan(order.get("outboundAt").asLong())
        assertThat(view.get("drugUsable").asBoolean()).isTrue()

        // 途中正常遥测
        val t = post("/api/orders/$orderId/telemetry",
            mapOf("tempC" to 4.5, "lat" to 31.23, "lng" to 121.47, "deliveryStatus" to "ON_ROUTE"), "RIDER", "rider-1")
        assertThat(t.statusCode.value()).isEqualTo(200)

        // 代收人签收
        val signed = post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "PROXY", "receiverName" to "李四", "receiverRelation" to "配偶", "idLast4" to "5678",
            "appearanceOk" to true, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        assertThat(signed.statusCode.value()).isEqualTo(200)
        assertThat(signed.body!!.get("status").asText()).isEqualTo("SIGNED")
        assertThat(signed.body!!.get("signOff").get("receiverType").asText()).isEqualTo("PROXY")

        // 用药回访
        val fu = post("/api/orders/$orderId/follow-ups",
            mapOf("result" to "OK", "note" to "患者已按提醒冷藏保存"), "CUSTOMER_SERVICE", "cs-1")
        assertThat(fu.statusCode.value()).isEqualTo(200)

        // 保温箱回收循环
        val recycled = post("/api/boxes/BOX-001/recycle", mapOf("conditionOk" to true), "WAREHOUSE", "warehouse-1")
        assertThat(recycled.statusCode.value()).isEqualTo(200)
        assertThat(recycled.body!!.get("status").asText()).isEqualTo("READY")
        assertThat(recycled.body!!.get("timesUsed").asInt()).isEqualTo(1)

        // 患者端视图
        val pv = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        assertThat(pv.get("status").asText()).isEqualTo("SIGNED")
        assertThat(pv.get("overTemp").get("occurred").asBoolean()).isFalse()
        assertThat(pv.get("medicationReminders").size()).isGreaterThan(0)
    }

    @Test
    fun `处方审核退回产生事件且阻断流程，补充后可重审`() {
        val orderId = createOrder()
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/1.jpg"), "PHARMACIST", "pharmacist-1")

        val rejected = post("/api/orders/$orderId/prescription-review",
            mapOf("approved" to false, "comment" to "处方照片模糊"), "PHARMACIST", "pharmacist-1")
        assertThat(rejected.statusCode.value()).isEqualTo(200)
        assertThat(rejected.body!!.get("status").asText()).isEqualTo("PRESCRIPTION_REJECTED")

        // 事件已产生
        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "PRESCRIPTION_REJECTED" }).isTrue()

        // 退回状态不能出库
        val outbound = post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-9",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")
        assertThat(outbound.statusCode.value()).isEqualTo(409)

        // 补充照片后重新审核通过
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/2.jpg"), "PHARMACIST", "pharmacist-1")
        val reReview = post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        assertThat(reReview.body!!.get("status").asText()).isEqualTo("PHARMACIST_REVIEWING")
    }

    @Test
    fun `骑手绕路超温：药师判不可用，赔付补发退款并记骑手责任`() {
        val orderId = createOrder()
        toDelivering(orderId)

        // 绕路 + 超温遥测
        val t = post("/api/orders/$orderId/telemetry",
            mapOf("tempC" to 12.5, "lat" to 31.3, "lng" to 121.5, "detour" to true), "RIDER", "rider-1")
        assertThat(t.statusCode.value()).isEqualTo(200)

        // 订单进入异常，待药师重判
        val usability = get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
        assertThat(usability.get("status").asText()).isEqualTo("DELIVERY_EXCEPTION")
        assertThat(usability.get("needsRejudgment").asBoolean()).isTrue()
        assertThat(usability.get("drugUsable").isNull).isTrue()

        // 异常事件类型为骑手绕路超温
        val incidents = get("/api/orders/$orderId/incidents", "COMPENSATION", "comp-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "RIDER_DETOUR_OVERTEMP" }).isTrue()

        // 超温状态下患者不能签收
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(409)

        // 药师判定不可继续使用
        val rej = post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to false, "comment" to "胰岛素长时间超温，效价无法保证"), "PHARMACIST", "pharmacist-1")
        assertThat(rej.body!!.get("status").asText()).isEqualTo("AWAITING_RESOLUTION")

        // 赔付建议：全额退款 + 补发 + 骑手全责
        val suggestion = get("/api/orders/$orderId/resolution/suggestion", "COMPENSATION", "comp-1").body!!
        assertThat(suggestion.get("suggestedReissue").asBoolean()).isTrue()
        assertThat(suggestion.get("suggestedRefundCents").asLong()).isEqualTo(100000)
        assertThat(suggestion.get("suggestedRiderLiability").asText()).isEqualTo("FULL")

        // 赔付处理：报损 + 补发 + 全额退款 + 骑手全责 + 风险提醒
        val resolution = post("/api/orders/$orderId/resolution", mapOf(
            "drugDisposition" to "SCRAPPED", "reissue" to true, "refundAmountCents" to 100000,
            "riderLiability" to "FULL", "riderFineCents" to 50000,
            "riskReminder" to "本单药品曾因超温报损，请勿使用原配送药品"
        ), "COMPENSATION", "comp-1")
        assertThat(resolution.statusCode.value()).isEqualTo(200)
        val r = resolution.body!!
        assertThat(r.get("insuranceRefundCents").asLong()).isEqualTo(70000)
        assertThat(r.get("selfPayRefundCents").asLong()).isEqualTo(30000)
        val newOrderId = r.get("reissueOrderId").asText()
        assertThat(newOrderId).isNotBlank()

        // 原订单已补发结案，事件全部关闭
        val oldOrder = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(oldOrder.get("status").asText()).isEqualTo("REISSUED")
        val incidentsAfter = get("/api/orders/$orderId/incidents", "COMPENSATION", "comp-1").body!!
        assertThat(incidentsAfter.all { it.get("status").asText() == "RESOLVED" }).isTrue()

        // 补发订单直接进入待出库，且不再收费
        val newOrder = get("/api/orders/$newOrderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(newOrder.get("status").asText()).isEqualTo("READY_FOR_OUTBOUND")
        assertThat(newOrder.get("totalAmountCents").asLong()).isEqualTo(0)
        assertThat(newOrder.get("reissueOfOrderId").asText()).isEqualTo(orderId)

        // 患者端：超温、补发、退款、风险提醒可见
        val pv = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        assertThat(pv.get("overTemp").get("occurred").asBoolean()).isTrue()
        assertThat(pv.get("overTemp").get("maxTempC").asDouble()).isEqualTo(12.5)
        assertThat(pv.get("reissue").get("newOrderId").asText()).isEqualTo(newOrderId)
        assertThat(pv.get("refund").get("status").asText()).isEqualTo("FULL")
        assertThat(pv.get("refund").get("refundedSelfPayCents").asLong()).isEqualTo(30000)
        assertThat(pv.get("riskReminders").any { it.asText().contains("超温") }).isTrue()
    }

    @Test
    fun `患者不在家：签收失败后药师判可用，恢复配送并成功签收`() {
        val orderId = createOrder()
        toDelivering(orderId)

        val attempt = post("/api/orders/$orderId/delivery-attempt",
            mapOf("result" to "PATIENT_NOT_HOME"), "RIDER", "rider-1")
        assertThat(attempt.body!!.get("status").asText()).isEqualTo("SIGN_FAILED")

        // 签收失败必须药师重新判断后才能继续
        val usability = get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
        assertThat(usability.get("needsRejudgment").asBoolean()).isTrue()

        val rej = post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to true, "comment" to "在途时间短，温度记录正常，可继续配送"), "PHARMACIST", "pharmacist-1")
        assertThat(rej.body!!.get("status").asText()).isEqualTo("DELIVERING")

        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `患者不在家：服务记录等待电话代收点，药师意见继续配送后签收`() {
        val orderId = createOrder()
        toDelivering(orderId)

        // 骑手到达无人签收：等待时间、电话结果、冷藏代收点、实测箱温
        val arrival = post("/api/orders/$orderId/not-home/arrival", mapOf(
            "waitedSeconds" to 180,
            "callResult" to "ANSWERED_AGREED_WAIT",
            "callNote" to "已拨打 2 次，患者称 10 分钟内到家",
            "pickupPointAvailable" to true,
            "pickupPointName" to "小区北门药房冷藏柜",
            "boxTempC" to 5.2
        ), "RIDER", "rider-1")
        assertThat(arrival.statusCode.value()).isEqualTo(200)
        assertThat(arrival.body!!.get("callResult").asText()).isEqualTo("ANSWERED_AGREED_WAIT")
        assertThat(arrival.body!!.get("pickupPointName").asText()).isEqualTo("小区北门药房冷藏柜")

        // 等待中继续补充
        val waiting = post("/api/orders/$orderId/not-home/waiting", mapOf(
            "waitedSeconds" to 420, "callResult" to "ANSWERED_AGREED_WAIT", "boxTempC" to 5.6
        ), "RIDER", "rider-1")
        assertThat(waiting.body!!.get("waitedSeconds").asLong()).isEqualTo(420)

        // 药店视角可查看完整处理进展：安全时限内、剩余时长约 120 分钟
        val case = get("/api/orders/$orderId/not-home", "PHARMACIST", "pharmacist-1").body!!
        assertThat(case.get("safetyLimitExceeded").asBoolean()).isFalse()
        assertThat(case.get("remainingBoxMinutes").asLong()).isBetween(110L, 120L)
        assertThat(case.get("canRedeliver").asBoolean()).isFalse()
        val pharmacy = get("/api/orders/$orderId", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(pharmacy.get("notHome").get("visit").get("waitedSeconds").asLong()).isEqualTo(420)

        // 药师意见：温控正常、未超安全时限 → 继续配送
        val opinion = post("/api/orders/$orderId/not-home/opinion",
            mapOf("opinion" to "CONTINUE_DELIVERY", "comment" to "箱温正常，等待时间可接受"), "PHARMACIST", "pharmacist-1")
        assertThat(opinion.body!!.get("pharmacistOpinion").asText()).isEqualTo("CONTINUE_DELIVERY")

        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `患者改约：重新计算保温箱可用时长，药师温控重评通过后按新时间签收`() {
        val orderId = createOrder()
        toDelivering(orderId)

        post("/api/orders/$orderId/not-home/arrival", mapOf(
            "waitedSeconds" to 300, "callResult" to "ANSWERED_REQUEST_RESCHEDULE",
            "callNote" to "患者在外地，要求今晚 8 点再送", "boxTempC" to 5.0
        ), "RIDER", "rider-1")

        // 患者同意改约：服务立即按剩余时长重新计算保温箱可用时长
        val newAppointmentAt = System.currentTimeMillis() + 30 * 60_000
        val rs = post("/api/orders/$orderId/reschedule",
            mapOf("newAppointmentAt" to newAppointmentAt), "PATIENT", "patient-1")
        assertThat(rs.statusCode.value()).isEqualTo(200)
        assertThat(rs.body!!.get("status").asText()).isEqualTo("PROPOSED")
        assertThat(rs.body!!.get("remainingBoxMinutes").asLong()).isBetween(110L, 120L)
        assertThat(rs.body!!.get("recalculatedValidUntil").asLong()).isGreaterThan(System.currentTimeMillis())

        // 改约通过前订单仍挂起，不能签收
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(409)

        // 药师温控重评通过
        val reviewed = post("/api/orders/$orderId/reschedule/review",
            mapOf("approved" to true, "comment" to "剩余保温时长可覆盖新预约时间"), "PHARMACIST", "pharmacist-1")
        assertThat(reviewed.body!!.get("status").asText()).isEqualTo("APPROVED")

        val order = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(order.get("status").asText()).isEqualTo("DELIVERING")
        assertThat(order.get("estimatedDeliveryAt").asLong()).isEqualTo(newAppointmentAt)

        // 患者端可见改约进展
        val pv = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        assertThat(pv.get("notHome").get("reschedule").get("status").asText()).isEqualTo("APPROVED")

        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `超过安全时限：禁止直接二次派送，改约温控重评被拒，改约失败生成退回任务`() {
        val orderId = createOrder()
        // 安全时限 0 分钟：出库即超时
        toDelivering(orderId, safetyLimitMinutes = 0)

        val arrival = post("/api/orders/$orderId/not-home/arrival", mapOf(
            "waitedSeconds" to 60, "callResult" to "NO_ANSWER", "boxTempC" to 6.0
        ), "RIDER", "rider-1")
        assertThat(arrival.body!!.get("safetyLimitExceeded").asBoolean()).isTrue()

        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "SAFETY_LIMIT_EXCEEDED" }).isTrue()

        // 超过安全时限后，药师不能直接判可用恢复二次派送
        val directRej = post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to true, "comment" to "再跑一趟"), "PHARMACIST", "pharmacist-1")
        assertThat(directRej.statusCode.value()).isEqualTo(409)

        // 药师“继续配送”意见同样被拦截
        val continueOpinion = post("/api/orders/$orderId/not-home/opinion",
            mapOf("opinion" to "CONTINUE_DELIVERY"), "PHARMACIST", "pharmacist-1")
        assertThat(continueOpinion.statusCode.value()).isEqualTo(409)

        // 可发起改约，但温控重评因已超安全时限不通过
        post("/api/orders/$orderId/reschedule",
            mapOf("newAppointmentAt" to System.currentTimeMillis() + 30 * 60_000), "PATIENT", "patient-1")
        val approve = post("/api/orders/$orderId/reschedule/review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        assertThat(approve.statusCode.value()).isEqualTo(409)
        val reject = post("/api/orders/$orderId/reschedule/review",
            mapOf("approved" to false, "comment" to "保温箱安全时限已过，须退回重新评估"), "PHARMACIST", "pharmacist-1")
        assertThat(reject.body!!.get("status").asText()).isEqualTo("REJECTED")

        // 改约失败 → 生成退回药店任务
        val fail = post("/api/orders/$orderId/reschedule/fail",
            mapOf("reason" to "患者今晚无法返回，联系改日再送失败"), "CUSTOMER_SERVICE", "cs-1")
        assertThat(fail.statusCode.value()).isEqualTo(200)
        assertThat(fail.body!!.get("status").asText()).isEqualTo("CREATED")
        val incidents2 = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents2.any { it.get("type").asText() == "RESCHEDULE_FAILED" }).isTrue()

        // 退回任务列表对仓库可见
        val tasks = get("/api/return-tasks", "WAREHOUSE", "warehouse-1").body!!
        assertThat(tasks.any { it.get("orderId").asText() == orderId }).isTrue()
    }

    @Test
    fun `退回药店：药师确认退回箱温前骑手不能带回，确认后到店报损结案并回收保温箱`() {
        val orderId = createOrder()
        toDelivering(orderId, boxNo = "BOX-010")

        post("/api/orders/$orderId/not-home/arrival", mapOf(
            "waitedSeconds" to 600, "callResult" to "WRONG_PERSON", "boxTempC" to 7.8
        ), "RIDER", "rider-1")

        // 药师意见：退回药店 → 生成退回任务
        val opinion = post("/api/orders/$orderId/not-home/opinion",
            mapOf("opinion" to "RETURN_TO_PHARMACY", "comment" to "无法联系患者，温控余量不足"), "PHARMACIST", "pharmacist-1")
        assertThat(opinion.statusCode.value()).isEqualTo(200)

        // 药师确认退回箱温前，骑手不能把药品带回
        val earlyBringBack = post("/api/orders/$orderId/not-home/return/bring-back", null, "RIDER", "rider-1")
        assertThat(earlyBringBack.statusCode.value()).isEqualTo(409)

        // 药师确认退回箱温
        val confirm = post("/api/orders/$orderId/not-home/return/confirm-temp",
            mapOf("boxTempC" to 6.2, "comment" to "开箱复测 6.2°C，同意带回"), "PHARMACIST", "pharmacist-1")
        assertThat(confirm.body!!.get("status").asText()).isEqualTo("RETURN_CONFIRMED")
        assertThat(confirm.body!!.get("confirmedBoxTempC").asDouble()).isEqualTo(6.2)

        // 骑手带回（不得继续派送），途中仍可上报箱温
        val bringBack = post("/api/orders/$orderId/not-home/return/bring-back", null, "RIDER", "rider-1")
        assertThat(bringBack.body!!.get("status").asText()).isEqualTo("IN_TRANSIT")
        val orderReturning = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(orderReturning.get("status").asText()).isEqualTo("RETURNING")
        // 退回途中不能继续派送/签收
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(409)
        assertThat(post("/api/orders/$orderId/telemetry",
            mapOf("tempC" to 6.5), "RIDER", "rider-1").statusCode.value()).isEqualTo(200)

        // 到店 → 待药师重新判断
        val arrived = post("/api/orders/$orderId/not-home/return/arrive-pharmacy", null, "RIDER", "rider-1")
        assertThat(arrived.body!!.get("status").asText()).isEqualTo("ARRIVED")
        val atStore = get("/api/orders/$orderId", "PHARMACIST", "pharmacist-1").body!!.get("order")
        assertThat(atStore.get("status").asText()).isEqualTo("AWAITING_RESOLUTION")

        // 待重判时不能直接结案
        val earlyResolve = post("/api/orders/$orderId/resolution",
            mapOf("drugDisposition" to "SCRAPPED", "refundAmountCents" to 100000), "COMPENSATION", "comp-1")
        assertThat(earlyResolve.statusCode.value()).isEqualTo(409)

        // 药师判不可用 → 报损结案
        post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to false, "comment" to "在途超时，退回后判定不可用"), "PHARMACIST", "pharmacist-1")
        val resolution = post("/api/orders/$orderId/resolution", mapOf(
            "drugDisposition" to "SCRAPPED", "reissue" to false, "refundAmountCents" to 100000
        ), "COMPENSATION", "comp-1")
        assertThat(resolution.statusCode.value()).isEqualTo(200)

        // 保温箱回收循环
        val recycled = post("/api/boxes/BOX-010/recycle",
            mapOf("conditionOk" to true), "WAREHOUSE", "warehouse-1")
        assertThat(recycled.body!!.get("status").asText()).isEqualTo("READY")
    }

    @Test
    fun `二次配送再次无人签收：归档历史服务记录与改约，可再次改约成功`() {
        val orderId = createOrder()
        toDelivering(orderId)

        // 第一次不在家 → 改约通过 → 恢复配送
        post("/api/orders/$orderId/not-home/arrival", mapOf(
            "waitedSeconds" to 60, "callResult" to "ANSWERED_REQUEST_RESCHEDULE", "boxTempC" to 5.0
        ), "RIDER", "rider-1")
        post("/api/orders/$orderId/reschedule",
            mapOf("newAppointmentAt" to System.currentTimeMillis() + 20 * 60_000), "PATIENT", "patient-1")
        val review = post("/api/orders/$orderId/reschedule/review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        assertThat(review.body!!.get("status").asText()).isEqualTo("APPROVED")

        // 二次到达仍无人：旧服务记录/改约归档，重新登记
        val arrival2 = post("/api/orders/$orderId/not-home/arrival", mapOf(
            "waitedSeconds" to 90, "callResult" to "ANSWERED_AGREED_WAIT", "boxTempC" to 5.4
        ), "RIDER", "rider-1")
        assertThat(arrival2.statusCode.value()).isEqualTo(200)
        assertThat(arrival2.body!!.get("waitedSeconds").asLong()).isEqualTo(90)

        val case = get("/api/orders/$orderId/not-home", "PHARMACIST", "pharmacist-1").body!!
        assertThat(case.get("visitHistory").size()).isEqualTo(1)
        assertThat(case.get("rescheduleHistory").size()).isEqualTo(1)
        assertThat(case.get("reschedule").isNull).isTrue()

        // 再次改约并温控重评通过 → 签收
        post("/api/orders/$orderId/reschedule",
            mapOf("newAppointmentAt" to System.currentTimeMillis() + 40 * 60_000), "PATIENT", "patient-1")
        val review2 = post("/api/orders/$orderId/reschedule/review",
            mapOf("approved" to true, "comment" to "剩余时长仍可覆盖"), "PHARMACIST", "pharmacist-1")
        assertThat(review2.body!!.get("status").asText()).isEqualTo("APPROVED")
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `药师同意冷藏代收点：骑手放置后恢复配送签收`() {
        val orderId = createOrder()
        toDelivering(orderId)

        post("/api/orders/$orderId/not-home/arrival", mapOf(
            "waitedSeconds" to 120, "callResult" to "ANSWERED_AGREED_WAIT",
            "pickupPointAvailable" to true, "pickupPointName" to "社区医院冷藏代收点", "boxTempC" to 4.8
        ), "RIDER", "rider-1")

        // 没有药师意见前骑手不能自行放置
        val early = post("/api/orders/$orderId/not-home/leave-pickup-point", null, "RIDER", "rider-1")
        assertThat(early.statusCode.value()).isEqualTo(409)

        val opinion = post("/api/orders/$orderId/not-home/opinion",
            mapOf("opinion" to "LEAVE_AT_PICKUP_POINT", "comment" to "代收点具备冷藏条件，可放置"),
            "PHARMACIST", "pharmacist-1")
        assertThat(opinion.body!!.get("pharmacistOpinion").asText()).isEqualTo("LEAVE_AT_PICKUP_POINT")

        // 无代收点记录时药师不能给出放置意见（另一单反例）
        val orderId2 = createOrder()
        toDelivering(orderId2, boxNo = "BOX-011")
        post("/api/orders/$orderId2/not-home/arrival",
            mapOf("waitedSeconds" to 60, "callResult" to "NO_ANSWER", "pickupPointAvailable" to false), "RIDER", "rider-1")
        val noPoint = post("/api/orders/$orderId2/not-home/opinion",
            mapOf("opinion" to "LEAVE_AT_PICKUP_POINT"), "PHARMACIST", "pharmacist-1")
        assertThat(noPoint.statusCode.value()).isEqualTo(409)

        val left = post("/api/orders/$orderId/not-home/leave-pickup-point", null, "RIDER", "rider-1")
        assertThat(left.body!!.get("status").asText()).isEqualTo("DELIVERING")
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `保温箱开盖异常：第二次开盖上报事件并冻结签收`() {
        val orderId = createOrder()
        toDelivering(orderId)

        post("/api/orders/$orderId/telemetry", mapOf("tempC" to 5.0, "boxOpened" to true), "RIDER", "rider-1")
        // 第一次开盖不异常
        assertThat(get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
            .get("needsRejudgment").asBoolean()).isFalse()

        post("/api/orders/$orderId/telemetry", mapOf("tempC" to 5.0, "boxOpened" to true), "RIDER", "rider-1")
        val u = get("/api/orders/$orderId/usability", "PHARMACIST", "pharmacist-1").body!!
        assertThat(u.get("needsRejudgment").asBoolean()).isTrue()
        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "BOX_OPENED_ABNORMAL" }).isTrue()
    }

    @Test
    fun `签收时外包装破损：签收失败进入药师重判，判可用后退回库存结案`() {
        val orderId = createOrder()
        toDelivering(orderId)

        val damaged = post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to false, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        assertThat(damaged.statusCode.value()).isEqualTo(409)

        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "PACKAGE_DAMAGED" }).isTrue()

        // 药师判定药品本身仍可使用 → 恢复配送
        val rej = post("/api/orders/$orderId/rejudgment",
            mapOf("usable" to true, "comment" to "仅外包装破损，药品完好"), "PHARMACIST", "pharmacist-1")
        assertThat(rej.body!!.get("status").asText()).isEqualTo("DELIVERING")

        // 患者仍要求退药 → 药师再次判断 → 进入待赔付处理
        post("/api/orders/$orderId/return-request", mapOf("reason" to "外包装破损，不敢使用"), "PATIENT", "patient-1")
        post("/api/orders/$orderId/rejudgment", mapOf("usable" to true, "comment" to "药品完好，可退回库存"), "PHARMACIST", "pharmacist-1")

        // 按退回库存 + 全额退款 + 补发结案
        val resolution = post("/api/orders/$orderId/resolution", mapOf(
            "drugDisposition" to "RETURNED_TO_STOCK", "reissue" to true, "refundAmountCents" to 100000
        ), "COMPENSATION", "comp-1")
        assertThat(resolution.statusCode.value()).isEqualTo(200)

        // 药师判可用却按报损处理会被拒绝（另起一单验证）
        val orderId2 = createOrder()
        toDelivering(orderId2, boxNo = "BOX-002")
        post("/api/orders/$orderId2/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to false, "tempLabelOk" to true, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        post("/api/orders/$orderId2/rejudgment", mapOf("usable" to true, "comment" to "药品完好"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId2/return-request", mapOf("reason" to "拒收"), "PATIENT", "patient-1")
        post("/api/orders/$orderId2/rejudgment", mapOf("usable" to true, "comment" to "药品完好，可退回库存"), "PHARMACIST", "pharmacist-1")
        val bad = post("/api/orders/$orderId2/resolution",
            mapOf("drugDisposition" to "SCRAPPED", "refundAmountCents" to 100000), "COMPENSATION", "comp-1")
        assertThat(bad.statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `签收后患者申请退药：药师判不可用，退款结案`() {
        val orderId = createOrder()
        toDelivering(orderId)
        assertThat(signOk(orderId).statusCode.value()).isEqualTo(200)

        val ret = post("/api/orders/$orderId/return-request", mapOf("reason" to "担心途中温度异常"), "PATIENT", "patient-1")
        assertThat(ret.body!!.get("status").asText()).isEqualTo("RETURN_REQUESTED")

        // 退药必须药师重新判断
        post("/api/orders/$orderId/rejudgment", mapOf("usable" to false, "comment" to "无法确认途中冷链完整"), "PHARMACIST", "pharmacist-1")
        val resolution = post("/api/orders/$orderId/resolution", mapOf(
            "drugDisposition" to "SCRAPPED", "reissue" to false, "refundAmountCents" to 100000,
            "riskReminder" to "如已使用本批药品，请密切观察并及时复诊"
        ), "COMPENSATION", "comp-1")
        assertThat(resolution.statusCode.value()).isEqualTo(200)

        val pv = get("/api/patient/orders/$orderId", "PATIENT", "patient-1").body!!
        assertThat(pv.get("status").asText()).isEqualTo("CLOSED")
        assertThat(pv.get("refund").get("status").asText()).isEqualTo("FULL")
        assertThat(pv.get("riskReminders").size()).isEqualTo(1)
    }

    @Test
    fun `温度标签异常导致签收失败，需药师重判`() {
        val orderId = createOrder()
        toDelivering(orderId)
        val resp = post("/api/orders/$orderId/sign", mapOf(
            "receiverType" to "SELF", "receiverName" to "张三", "idLast4" to "1234",
            "appearanceOk" to true, "tempLabelOk" to false, "reminderAcknowledged" to true
        ), "PATIENT", "patient-1")
        assertThat(resp.statusCode.value()).isEqualTo(409)
        val incidents = get("/api/orders/$orderId/incidents", "CUSTOMER_SERVICE", "cs-1").body!!
        assertThat(incidents.any { it.get("type").asText() == "OVER_TEMPERATURE" }).isTrue()
    }

    @Test
    fun `未培训骑手不能取药，培训通过后可以`() {
        val orderId = createOrder()
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/1.jpg"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/pharmacist-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-2")
        post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-003",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")

        val denied = post("/api/orders/$orderId/pickup", null, "RIDER", "rider-new")
        assertThat(denied.statusCode.value()).isEqualTo(409)

        trainRider("rider-new")
        val ok = post("/api/orders/$orderId/pickup", null, "RIDER", "rider-new")
        assertThat(ok.statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `权限与归属：角色校验和患者越权拦截`() {
        val orderId = createOrder()

        // 缺少请求头
        assertThat(rest.exchange("/api/orders/$orderId", HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), JsonNode::class.java)
            .statusCode.value()).isEqualTo(401)

        // 患者不能调用药师接口
        assertThat(post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PATIENT", "patient-1")
            .statusCode.value()).isEqualTo(403)

        // 其他患者不能查看/签收
        toDelivering(orderId)
        assertThat(get("/api/patient/orders/$orderId", "PATIENT", "patient-2").statusCode.value()).isEqualTo(403)
        assertThat(signOk(orderId, patientId = "patient-2").statusCode.value()).isEqualTo(403)

        // 非本单骑手不能上报遥测
        trainRider("rider-2")
        assertThat(post("/api/orders/$orderId/telemetry", mapOf("tempC" to 5.0), "RIDER", "rider-2")
            .statusCode.value()).isEqualTo(403)
    }

    @Test
    fun `医保自费拆分与部分退款按比例拆分`() {
        val orderId = createOrder(total = 100000, insurance = 70000)
        val split = get("/api/orders/$orderId/payment-split", "INSURANCE", "insurance-1").body!!
        assertThat(split.get("insuranceCents").asLong()).isEqualTo(70000)
        assertThat(split.get("selfPayCents").asLong()).isEqualTo(30000)

        // 部分退款 500 元 → 医保 350 / 自费 150
        val refund = post("/api/orders/$orderId/refund", mapOf("amountCents" to 50000), "INSURANCE", "insurance-1")
        assertThat(refund.statusCode.value()).isEqualTo(200)
        assertThat(refund.body!!.get("insuranceRefundCents").asLong()).isEqualTo(35000)
        assertThat(refund.body!!.get("selfPayRefundCents").asLong()).isEqualTo(15000)
        assertThat(refund.body!!.get("payment").get("refundStatus").asText()).isEqualTo("PARTIAL")

        // 超额退款被拒绝
        assertThat(post("/api/orders/$orderId/refund", mapOf("amountCents" to 60000), "INSURANCE", "insurance-1")
            .statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `出库校验：超温禁止出库，占用中的保温箱不能再用`() {
        val orderId = createOrder()
        post("/api/orders/$orderId/prescription-photos", mapOf("url" to "https://img.example.com/rx/1.jpg"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId/pharmacist-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-2")

        // 出库温度超范围
        val hot = post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-004",
            "icePackCount" to 1, "outboundTempC" to 15.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")
        assertThat(hot.statusCode.value()).isEqualTo(400)

        // 正常出库后，同一保温箱不能再用于其他订单
        assertThat(post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B1", "expiryDate" to "2027-01-01", "boxNo" to "BOX-004",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1").statusCode.value()).isEqualTo(200)

        val orderId2 = createOrder()
        post("/api/orders/$orderId2/prescription-photos", mapOf("url" to "https://img.example.com/rx/2.jpg"), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId2/prescription-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-1")
        post("/api/orders/$orderId2/pharmacist-review", mapOf("approved" to true), "PHARMACIST", "pharmacist-2")
        val conflict = post("/api/orders/$orderId2/outbound", mapOf(
            "batchNo" to "B2", "expiryDate" to "2027-01-01", "boxNo" to "BOX-004",
            "icePackCount" to 1, "outboundTempC" to 5.0, "etaMinutes" to 30
        ), "WAREHOUSE", "warehouse-1")
        assertThat(conflict.statusCode.value()).isEqualTo(409)
    }
}
