package com.pharmacy.coldchain.domain

/** 冷藏药品配送订单（Redis 中以 JSON 单文档存储于 order:{id}） */
data class ColdChainOrder(
    val id: String,
    val patientId: String,
    val patientName: String,
    val drugType: DrugType,
    val drugName: String,
    val quantity: Int,
    val prescriptionNo: String,
    val totalAmountCents: Long,
    val insuranceAmountCents: Long,
    val selfPayAmountCents: Long,
    var status: OrderStatus = OrderStatus.PRESCRIPTION_REVIEWING,
    var usability: Usability = Usability.NOT_OUTBOUND,

    // 处方审核
    var prescriptionReviewer: String? = null,
    var prescriptionReviewComment: String? = null,
    var prescriptionReviewedAt: Long? = null,

    // 药师复核
    var pharmacistReviewer: String? = null,
    var pharmacistReviewComment: String? = null,
    var pharmacistReviewedAt: Long? = null,

    // 出库记录
    var batchNo: String? = null,
    var expiryDate: String? = null,          // YYYY-MM-DD
    var boxNo: String? = null,
    var icePackCount: Int? = null,
    var outboundTempC: Double? = null,
    var estimatedDeliveryAt: Long? = null,
    var outboundAt: Long? = null,
    var warehouseOperator: String? = null,

    // 配送过程
    var riderId: String? = null,
    var pickedUpAt: Long? = null,
    var boxOpenCount: Int = 0,
    var lastTempC: Double? = null,
    var overTemp: Boolean = false,
    var lastDeliveryStatus: String? = null,

    // 签收
    var signOff: SignOff? = null,

    // 退药
    var returnReason: String? = null,

    // 补发关联
    var reissueOfOrderId: String? = null,
    var reissuedOrderId: String? = null,

    // 用药提醒（按药品类型生成）与用药风险提醒（赔付/药师追加）
    val medicationReminders: List<String> = emptyList(),
    val riskReminders: MutableList<String> = mutableListOf(),

    val createdAt: Long = 0,
    var updatedAt: Long = 0
)

/** 签收记录 */
data class SignOff(
    val orderId: String,
    val receiverType: ReceiverType,   // SELF 本人 / PROXY 代收人
    val receiverName: String,
    val receiverRelation: String? = null, // 代收人与患者关系
    val idLast4: String,              // 身份证后四位
    val appearanceOk: Boolean,        // 药品外观完好
    val tempLabelOk: Boolean,         // 温度标签正常
    val reminderAcknowledged: Boolean,// 已知晓用药提醒
    val signedBy: String,             // 操作的患者账号
    val at: Long
)

/** 配送遥测（箱内温度 / 路线 / 开箱 / 配送状态），Redis list: telemetry:{orderId} */
data class TelemetryReading(
    val orderId: String,
    val riderId: String,
    val tempC: Double,
    val lat: Double? = null,
    val lng: Double? = null,
    val boxOpened: Boolean = false,
    val detour: Boolean = false,
    val deliveryStatus: String? = null,
    val at: Long
)

/** 异常事件，把各角色连接到同一订单 */
data class Incident(
    val id: String,
    val orderId: String,
    val type: IncidentType,
    val reporterRole: String,
    val reporterId: String,
    val detail: String,
    var status: IncidentStatus = IncidentStatus.OPEN,
    var resolutionNote: String? = null,
    var resolvedBy: String? = null,
    val createdAt: Long,
    var resolvedAt: Long? = null
)

/** 药师重新判断记录（出库后签收失败/超温/退药时必填） */
data class Rejudgment(
    val id: String,
    val orderId: String,
    val pharmacistId: String,
    val usable: Boolean,
    val comment: String,
    val createdAt: Long
)

/** 最终处理结果：报损/补发/退款/骑手责任/用药风险提醒 */
data class Resolution(
    val orderId: String,
    val drugDisposition: DrugDisposition,
    val reissue: Boolean,
    val reissueOrderId: String? = null,
    val refundAmountCents: Long = 0,
    val insuranceRefundCents: Long = 0,
    val selfPayRefundCents: Long = 0,
    val riderLiability: RiderLiability = RiderLiability.NONE,
    val riderFineCents: Long = 0,
    val riskReminder: String? = null,
    val resolvedBy: String,
    val createdAt: Long
)

/** 医保/自费拆分及退款进度 */
data class PaymentSplit(
    val orderId: String,
    val totalCents: Long,
    val insuranceCents: Long,
    val selfPayCents: Long,
    var refundedInsuranceCents: Long = 0,
    var refundedSelfPayCents: Long = 0,
    var refundStatus: RefundStatus = RefundStatus.NONE
)

/** 冷链保温箱（循环使用） */
data class ColdChainBox(
    val boxNo: String,
    var status: BoxStatus = BoxStatus.READY,
    var timesUsed: Int = 0,
    var currentOrderId: String? = null,
    val history: MutableList<String> = mutableListOf(),
    var conditionNote: String? = null
)

/** 骑手冷链培训记录 */
data class TrainingRecord(
    val riderId: String,
    val course: String,
    val score: Int,
    val passed: Boolean,
    val at: Long
)

/** 患者用药回访 */
data class FollowUp(
    val id: String,
    val orderId: String,
    val staffId: String,
    val result: FollowUpResult,
    val note: String? = null,
    val at: Long
)

/** 处方照片归档 */
data class PrescriptionPhoto(
    val id: String,
    val orderId: String,
    val url: String,
    val uploadedBy: String,
    val note: String? = null,
    val at: Long
)
