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
    SIGN_FAILED,             // 签收失败（患者不在家）
    RETURN_REQUESTED,        // 患者申请退药
    AWAITING_RESOLUTION,     // 待赔付/客服最终处理
    SIGNED,                  // 已签收
    REISSUED,                // 已补发（生成新订单）
    RETURNING,               // 药师确认退回箱温后，骑手将药品送回药店中（不得继续派送）
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
    BOX_OPENED_ABNORMAL,     // 保温箱开盖异常
    PACKAGE_DAMAGED,         // 药品外包装破损
    PATIENT_RETURN_REQUEST,  // 患者申请退药
    SAFETY_LIMIT_EXCEEDED,   // 患者不在家滞留超过保温箱安全时限，禁止直接二次派送
    RESCHEDULE_FAILED,       // 患者改约失败（超时/拒绝/联系不上）
    PHARMACIST_UNUSABLE      // 药师判定不可继续使用
}

enum class IncidentStatus { OPEN, RESOLVED }

enum class ReceiverType { SELF, PROXY }

/** 骑手到达后联系患者的电话结果 */
enum class CallResult {
    ANSWERED_AGREED_WAIT,     // 已接通，患者同意稍候
    ANSWERED_REQUEST_RESCHEDULE, // 已接通，患者要求改约
    ANSWERED_REFUSED,         // 已接通，患者拒收
    NO_ANSWER,                // 无人接听
    WRONG_PERSON              // 非本人/号码有误
}

/** 患者改约进展 */
enum class RescheduleStatus {
    PROPOSED,        // 已发起改约，等待药师温控重评
    APPROVED,        // 药师重评通过，按新预约时间继续配送
    REJECTED,        // 药师重评不通过（保温箱安全时限不足/温度异常）
    FAILED           // 改约失败（患者确认无法签收等），转退回药店
}

/** 退回药店任务状态 */
enum class ReturnTaskStatus {
    CREATED,         // 已生成退回任务，等待药师确认退回箱温
    RETURN_CONFIRMED,// 药师已确认退回箱温，骑手可以带回
    IN_TRANSIT,      // 骑手送回药店途中
    ARRIVED          // 药品已退回药店（保温箱可回收，药品待药师处置）
}

/** 药师对“患者不在家”服务记录的处置意见 */
enum class PharmacistOpinion {
    CONTINUE_DELIVERY,      // 温控正常且未超安全时限，可继续等待/配送
    LEAVE_AT_PICKUP_POINT,  // 可放置在冷藏代收点
    RESCHEDULE,             // 建议与患者改约签收
    RETURN_TO_PHARMACY      // 建议退回药店
}

/** 最终药品处置方式 */
enum class DrugDisposition { SCRAPPED, RETURNED_TO_STOCK }

enum class RiderLiability { NONE, PARTIAL, FULL }

enum class RefundStatus { NONE, PARTIAL, FULL }

enum class BoxStatus { READY, IN_USE, RETIRED }

enum class FollowUpResult { OK, ADVERSE_REACTION, NO_ANSWER }

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
