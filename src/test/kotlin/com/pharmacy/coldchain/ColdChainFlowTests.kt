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
    private fun toDelivering(orderId: String, boxNo: String = "BOX-001", riderId: String = "rider-1") {
        assertThat(post("/api/orders/$orderId/prescription-photos",
            mapOf("url" to "https://img.example.com/rx/$orderId.jpg"), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/prescription-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-1").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/pharmacist-review",
            mapOf("approved" to true), "PHARMACIST", "pharmacist-2").statusCode.value()).isEqualTo(200)
        assertThat(post("/api/orders/$orderId/outbound", mapOf(
            "batchNo" to "B20260901", "expiryDate" to "2027-06-30", "boxNo" to boxNo,
            "icePackCount" to 2, "outboundTempC" to 5.0, "etaMinutes" to 45
        ), "WAREHOUSE", "warehouse-1").statusCode.value()).isEqualTo(200)
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
