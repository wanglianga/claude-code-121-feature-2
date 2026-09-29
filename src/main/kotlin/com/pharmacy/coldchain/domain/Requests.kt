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
    /** 保温箱额定保温时长（分钟），不传时按冰袋数量默认：每袋 60 分钟，至少 120 分钟 */
    val boxRatedMinutes: Int? = null
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

/** 骑手到达患者家无人签收：登记服务记录 */
data class NotHomeArrivalRequest(
    val waitedMinutes: Int,                       // 门口等待时长（分钟）
    val phoneResult: PhoneResult,                 // 电话结果
    val phoneNote: String? = null,
    val coolPoint: CoolPointAvailability,         // 是否可放置冷藏代收点
    val coolPointNote: String? = null
)

/** 药师对患者不在家订单的处置意见 */
data class NotHomePharmacistDecisionRequest(
    val opinion: PharmacistOpinion,   // WAIT_AND_RESCHEDULE / BRING_BACK
    val comment: String? = null
)

/** 患者同意改约签收：重新计算保温箱可用时长 */
data class RescheduleRequest(
    val etaMinutes: Int,               // 改约后预计再派送在途时长（分钟）
    val note: String? = null
)

/** 骑手把药品带回药店（药师要求后方可操作） */
data class BringBackRequest(
    val boxTempC: Double,              // 骑手交接时上报的箱内温度
    val note: String? = null
)

/** 药师确认退回箱温（重新评估温控的前提） */
data class ReturnTempConfirmRequest(
    val boxTempC: Double,
    val comment: String? = null
)

/** 药店接收退回药品 */
data class ReturnTaskReceiveRequest(
    val note: String? = null
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
    val notHome: NotHomeServiceRecord? = null,   // 患者不在家服务记录
    val returnTask: ReturnTask? = null           // 退回药店任务
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
    val notHomeStage: NotHomeStage? = null,           // 不在家处理阶段（改约/退回进度）
    val rescheduleEtaMinutes: Int? = null,
    val boxSafeUntil: Long? = null
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
