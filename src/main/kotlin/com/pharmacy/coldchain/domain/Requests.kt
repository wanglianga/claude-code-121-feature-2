package com.pharmacy.coldchain.domain

// ---------- 请求 DTO ----------

data class CreateOrderRequest(
    val patientId: String,
    val patientName: String,
    val drugType: DrugType,
    val drugName: String,
    val quantity: Int = 1,
    val prescriptionNo: String,
    val totalAmountCents: Long,
    val insuranceAmountCents: Long
)

data class ReviewRequest(
    val approved: Boolean,
    val comment: String? = null
)

data class OutboundRequest(
    val batchNo: String,
    val expiryDate: String,   // YYYY-MM-DD
    val boxNo: String,
    val icePackCount: Int,
    val outboundTempC: Double,
    val etaMinutes: Long,
    /** 保温箱安全配送时限（分钟，出库起算），不传按默认 120 分钟 */
    val safetyLimitMinutes: Long? = null
)

data class TelemetryRequest(
    val tempC: Double,
    val lat: Double? = null,
    val lng: Double? = null,
    val boxOpened: Boolean = false,
    val detour: Boolean = false,
    val deliveryStatus: String? = null
)

data class DeliveryAttemptRequest(
    val result: String   // 目前支持 PATIENT_NOT_HOME
)

/** 骑手到达后无人签收：首次建立“患者不在家”服务记录 */
data class NotHomeArrivalRequest(
    val callResult: CallResult? = null,      // 电话联系结果
    val callNote: String? = null,            // 通话备注（呼叫次数、联系人等）
    val waitedSeconds: Long = 0,             // 已等待时长（秒）
    val pickupPointAvailable: Boolean = false, // 是否有可放置的冷藏代收点
    val pickupPointName: String? = null,     // 冷藏代收点名称
    val boxTempC: Double? = null             // 骑手实测箱温（°C）
)

/** 等待过程中持续补充：等待时间 / 电话结果 / 代收点 / 箱温 */
data class WaitingUpdateRequest(
    val waitedSeconds: Long,                 // 累计等待时长（秒，单调递增）
    val callResult: CallResult? = null,
    val callNote: String? = null,
    val pickupPointAvailable: Boolean = false,
    val pickupPointName: String? = null,
    val boxTempC: Double? = null
)

/** 药师对患者不在家服务记录的处置意见 */
data class PharmacistOpinionRequest(
    val opinion: PharmacistOpinion,
    val comment: String? = null
)

/** 患者（或客服代患者）同意改约签收 */
data class RescheduleRequest(
    val newAppointmentAt: Long   // 新预约签收时间（epoch 毫秒）
)

/** 药师对改约进行温控重评 */
data class RescheduleReviewRequest(
    val approved: Boolean,
    val comment: String? = null
)

/** 改约失败（患者确认无法签收 / 联系不上等） */
data class RescheduleFailRequest(
    val reason: String
)

/** 药师确认退回箱温（骑手带回前的前置条件） */
data class ConfirmReturnTempRequest(
    val boxTempC: Double,
    val comment: String? = null
)

data class SignRequest(
    val receiverType: ReceiverType,
    val receiverName: String,
    val receiverRelation: String? = null,
    val idLast4: String,
    val appearanceOk: Boolean,
    val tempLabelOk: Boolean,
    val reminderAcknowledged: Boolean
)

data class ReturnRequestBody(val reason: String)

data class RejudgmentRequest(
    val usable: Boolean,
    val comment: String
)

data class IncidentRequest(
    val type: IncidentType,
    val detail: String
)

data class ResolveIncidentRequest(val note: String)

data class ResolutionRequest(
    val drugDisposition: DrugDisposition,
    val reissue: Boolean = false,
    val refundAmountCents: Long = 0,
    val riderLiability: RiderLiability = RiderLiability.NONE,
    val riderFineCents: Long = 0,
    val riskReminder: String? = null
)

data class RefundRequest(val amountCents: Long)

data class PhotoRequest(
    val url: String,
    val note: String? = null
)

data class RecycleRequest(
    val conditionOk: Boolean,
    val note: String? = null
)

data class TrainingRequest(
    val course: String,
    val score: Int,
    val passed: Boolean
)

data class FollowUpRequest(
    val result: FollowUpResult,
    val note: String? = null
)

// ---------- 视图 DTO ----------

data class TelemetrySummary(
    val count: Int,
    val maxTempC: Double,
    val minTempC: Double,
    val lastTempC: Double,
    val boxOpenCount: Int,
    val lastLat: Double?,
    val lastLng: Double?
)

/** 药店视角：按订单看到药品是否仍可使用，以及全过程记录 */
data class PharmacyOrderView(
    val order: ColdChainOrder,
    val drugUsable: Boolean?,        // true 可用 / false 不可用 / null 未出库或待判断
    val needsRejudgment: Boolean,    // 是否需要药师重新判断
    val telemetrySummary: TelemetrySummary?,
    val incidents: List<Incident>,
    val rejudgments: List<Rejudgment>,
    val resolution: Resolution?,
    val payment: PaymentSplit?,
    val box: ColdChainBox?,
    val followUps: List<FollowUp>,
    val notHome: NotHomeCaseView? = null   // 患者不在家处理进展（无则 null）
)

/** 患者不在家完整处理视图：服务记录 / 改约 / 退回任务 / 保温箱剩余可用时长 */
data class NotHomeCaseView(
    val visit: ServiceVisit?,
    val reschedule: Reschedule?,
    val returnTask: ReturnTask?,
    val boxValidUntil: Long?,
    val remainingBoxMinutes: Long?,      // 保温箱剩余可用时长（分钟，负数表示已超时）
    val safetyLimitMinutes: Long?,
    val safetyLimitExceeded: Boolean,
    val canRedeliver: Boolean,           // 当前是否允许骑手继续派送/二次派送
    val visitHistory: List<ServiceVisit> = emptyList(),   // 二次配送尝试时归档的历次服务记录
    val rescheduleHistory: List<Reschedule> = emptyList() // 历次改约记录
)

data class OverTempInfo(
    val occurred: Boolean,
    val maxTempC: Double?,
    val allowedRange: String
)

data class ReissueInfo(
    val requested: Boolean,
    val newOrderId: String?,
    val newOrderStatus: OrderStatus?
)

data class RefundInfo(
    val status: RefundStatus,
    val refundedSelfPayCents: Long,
    val refundedTotalCents: Long
)

/** 患者视角：超温、补发、退款、用药提醒的当前状态 */
data class PatientOrderView(
    val orderId: String,
    val status: OrderStatus,
    val drugName: String,
    val drugType: DrugType,
    val overTemp: OverTempInfo,
    val reissue: ReissueInfo,
    val refund: RefundInfo,
    val medicationReminders: List<String>,
    val riskReminders: List<String>,
    val sign: SignOff?,
    val estimatedDeliveryAt: Long?,
    val notHome: NotHomeCaseView? = null
)

/** 赔付处理建议（根据异常事件自动推导） */
data class ResolutionSuggestion(
    val orderId: String,
    val suggestedDrugDisposition: DrugDisposition,
    val suggestedReissue: Boolean,
    val suggestedRefundCents: Long,
    val suggestedRiderLiability: RiderLiability,
    val suggestedRiskReminder: String?,
    val openIncidentTypes: List<IncidentType>
)
