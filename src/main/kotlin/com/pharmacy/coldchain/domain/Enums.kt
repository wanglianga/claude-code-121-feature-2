package com.pharmacy.coldchain.domain

/** 冷藏药品类型，均要求 2-8°C 冷链 */
enum class DrugType(val minTempC: Double, val maxTempC: Double, val label: String) {
    INSULIN(2.0, 8.0, "胰岛素"),
    BIOLOGICAL(2.0, 8.0, "生物制剂"),
    EYE_DROPS(2.0, 8.0, "眼药水"),
    SPECIAL_COLD_CHAIN(2.0, 8.0, "特殊冷藏药品")
}

enum class OrderStatus {
    PRESCRIPTION_REVIEWING,  // 处方审核中
    PRESCRIPTION_REJECTED,   // 处方审核退回（可补充材料后重新送审）
    PHARMACIST_REVIEWING,    // 药师复核中
    READY_FOR_OUTBOUND,      // 待出库
    OUTBOUND,                // 已出库，待骑手取药
    DELIVERING,              // 配送中
    DELIVERY_EXCEPTION,      // 配送异常（超温 / 开盖异常 / 包装破损）
    SIGN_FAILED,             // 签收失败（患者不在家，等待药师处置意见）
    RESCHEDULED,             // 患者同意改约，已重算保温箱可用时长，等待再次派送
    RETURNED_TO_PHARMACY,    // 药品已带回药店，药师确认退回箱温后重新评估温控
    RETURN_REQUESTED,        // 患者申请退药
    AWAITING_RESOLUTION,     // 待赔付/客服最终处理
    SIGNED,                  // 已签收
    REISSUED,                // 已补发（生成新订单）
    CLOSED                   // 已结案
}

/** 药品是否仍可使用（药店视角的核心字段） */
enum class Usability {
    NOT_OUTBOUND,        // 未出库
    USABLE,              // 可继续使用
    PENDING_REJUDGMENT,  // 出库后出现签收失败/超温/退药，待药师重新判断
    UNUSABLE_SCRAPPED    // 药师判定不可继续使用（报损）
}

enum class IncidentType {
    PRESCRIPTION_REJECTED,   // 处方审核退回
    OVER_TEMPERATURE,        // 超温
    RIDER_DETOUR_OVERTEMP,   // 骑手绕路导致超温
    PATIENT_NOT_HOME,        // 患者不在家
    PATIENT_NOT_HOME_RETURNED, // 患者不在家且改约失败，药品退回药店（重新评估温控）
    BOX_OPENED_ABNORMAL,     // 保温箱开盖异常
    PACKAGE_DAMAGED,         // 药品外包装破损
    PATIENT_RETURN_REQUEST,  // 患者申请退药
    PHARMACIST_UNUSABLE      // 药师判定不可继续使用
}

enum class IncidentStatus { OPEN, RESOLVED }

enum class ReceiverType { SELF, PROXY }

/** 最终药品处置方式 */
enum class DrugDisposition { SCRAPPED, RETURNED_TO_STOCK }

enum class RiderLiability { NONE, PARTIAL, FULL }

enum class RefundStatus { NONE, PARTIAL, FULL }

enum class BoxStatus { READY, IN_USE, RETIRED }

enum class FollowUpResult { OK, ADVERSE_REACTION, NO_ANSWER }

/** 患者不在家服务记录的流转阶段 */
enum class NotHomeStage {
    ARRIVED_NO_ANSWER,  // 骑手到达无人签收
    AWAITING_DECISION,  // 等待药师处置意见
    RESCHEDULED,        // 患者同意改约，保温箱可用时长已重算
    BRING_BACK,         // 药师要求带回，骑手带回途中
    RETURNED,           // 已带回药店，待药师确认退回箱温
    REJUDGED,           // 药师已确认退回箱温并完成温控重新评估
    CLOSED              // 已签收或已转退回药店任务，记录关闭
}

/** 骑手致电患者的结果 */
enum class PhoneResult {
    NO_ANSWER,          // 无人接听
    ANSWERED_AGREE_RESCHEDULE, // 接通，患者同意改约
    ANSWERED_REJECT,    // 接通，患者拒绝改约/无法签收
    INVALID_CONTACT     // 联系方式无效
}

/** 是否可放置在冷藏代收点 */
enum class CoolPointAvailability {
    AVAILABLE,          // 附近有冷藏代收点且可放置
    NOT_AVAILABLE,      // 附近无冷藏代收点
    PATIENT_REFUSED     // 患者拒绝放置代收点
}

/** 药师对不在家订单的处置意见 */
enum class PharmacistOpinion {
    WAIT_AND_RESCHEDULE, // 安全时限内：等待并与患者改约
    BRING_BACK           // 超过安全时限或温控无把握：带回药店重新评估
}

/** 改约时保温箱可用时长的重算结果 */
enum class BoxDurationRecalc {
    WITHIN_SAFE_LIMIT,      // 剩余保温时长足以覆盖改约后预计在途时间
    EXCEEDED_SAFE_LIMIT     // 超出保温箱安全时限，不能改约，必须带回重新评估
}

/** 退回药店任务状态 */
enum class ReturnTaskStatus {
    CREATED,     // 已生成，等待骑手带回
    IN_TRANSIT,  // 骑手带回途中
    RECEIVED,    // 药店已接收
    CANCELLED    // 取消（改约成功等情况）
}

enum class Role {
    PHARMACIST,        // 药师
    WAREHOUSE,         // 仓库
    RIDER,             // 骑手
    PATIENT,           // 患者
    CUSTOMER_SERVICE,  // 客服
    INSURANCE,         // 医保结算
    COMPENSATION,      // 赔付人员
    ADMIN
}
