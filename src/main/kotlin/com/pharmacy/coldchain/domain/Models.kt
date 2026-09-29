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
    /** 保温箱安全配送时限（分钟）：出库起算，超过后不能直接二次派送，必须药师重新评估温控 */
    var safetyLimitMinutes: Long? = null,
    /** 取药时保温箱可用时长截止时间（改约时会基于剩余时长重新计算） */
    var boxValidUntil: Long? = null,

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

/**
 * 患者不在家服务记录（骑手到达后无人签收时创建，Redis: service-visit:{orderId}）。
 * 记录等待时间、电话结果、是否可放置冷藏代收点与药师意见。
 */
data class ServiceVisit(
    val id: String,
    val orderId: String,
    val riderId: String,
    val arrivedAt: Long,                 // 骑手到达时间
    var waitedSeconds: Long = 0,         // 累计等待时长（秒）
    var callResult: CallResult? = null,  // 电话联系结果
    var callNote: String? = null,        // 通话备注（呼叫次数、联系人等）
    /** 骑手是否发现可用的冷藏代收点 */
    var pickupPointAvailable: Boolean = false,
    var pickupPointName: String? = null,  // 冷藏代收点名称
    /** 骑手实测的当前箱温（°C），供药师判断 */
    var boxTempC: Double? = null,
    /** 药师处置意见 */
    var pharmacistOpinion: PharmacistOpinion? = null,
    var pharmacistId: String? = null,
    var pharmacistComment: String? = null,
    var pharmacistAdvisedAt: Long? = null,
    /** 是否已超过保温箱安全时限 */
    var safetyLimitExceeded: Boolean = false,
    var updatedAt: Long
)

/** 患者改约记录（同意改约签收时创建，Redis: reschedule:{orderId}） */
data class Reschedule(
    val id: String,
    val orderId: String,
    val requestedBy: String,             // 发起方（患者账号/骑手/客服）
    val newAppointmentAt: Long,          // 新预约签收时间
    var status: RescheduleStatus,
    /** 药师温控重评：改约时点保温箱剩余可用时长（分钟） */
    var remainingBoxMinutes: Long? = null,
    /** 改约后重新计算的保温箱可用时长截止时间 */
    var recalculatedValidUntil: Long? = null,
    var pharmacistId: String? = null,
    var pharmacistComment: String? = null,
    var reviewedAt: Long? = null,
    var failureReason: String? = null,   // 改约失败原因
    val createdAt: Long,
    var updatedAt: Long
)

/** 改约失败/药师要求退回时生成的退回药店任务（Redis: return-task:{orderId}） */
data class ReturnTask(
    val id: String,
    val orderId: String,
    val riderId: String,
    var status: ReturnTaskStatus,
    val reason: String,
    /** 药师确认的退回箱温（°C），未确认前骑手不能把药品带回继续派送 */
    var confirmedBoxTempC: Double? = null,
    var pharmacistId: String? = null,
    var pharmacistComment: String? = null,
    var confirmedAt: Long? = null,
    var pickedUpAt: Long? = null,        // 骑手开始送回
    var arrivedAt: Long? = null,         // 到达药店
    val createdAt: Long,
    var updatedAt: Long
)
