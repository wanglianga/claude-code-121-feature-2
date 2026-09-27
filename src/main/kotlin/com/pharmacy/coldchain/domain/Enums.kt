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
