package com.pharmacy.coldchain.service

import com.pharmacy.coldchain.common.BadRequestException
import com.pharmacy.coldchain.common.ConflictException
import com.pharmacy.coldchain.common.ForbiddenException
import com.pharmacy.coldchain.domain.ColdChainOrder
import com.pharmacy.coldchain.domain.DeliveryAttemptRequest
import com.pharmacy.coldchain.domain.IncidentType
import com.pharmacy.coldchain.domain.OrderStatus
import com.pharmacy.coldchain.domain.Role
import com.pharmacy.coldchain.domain.TelemetryReading
import com.pharmacy.coldchain.domain.TelemetryRequest
import com.pharmacy.coldchain.domain.Usability
import com.pharmacy.coldchain.repo.JsonStore
import com.pharmacy.coldchain.repo.get
import com.pharmacy.coldchain.repo.lrangeJson
import com.pharmacy.coldchain.repo.OrderRepository
import org.springframework.stereotype.Service

/** 骑手取药、途中遥测（温度/路线/开箱/状态）、配送尝试 */
@Service
class DeliveryService(
    private val orders: OrderRepository,
    private val store: JsonStore,
    private val incidents: IncidentService,
    private val riders: RiderService,
    private val orderService: OrderService
) {

    companion object {
        /** 配送途中允许的最大开盖次数，超过即判定开盖异常 */
        const val MAX_BOX_OPENS = 1
    }

    fun pickup(orderId: String, riderId: String): ColdChainOrder {
        val o = orders.require(orderId)
        orderService.requireStatus(o, OrderStatus.OUTBOUND)
        if (!riders.isTrained(riderId)) {
            throw ConflictException("骑手 $riderId 未通过冷链配送培训，不能取冷藏药品")
        }
        o.riderId = riderId
        o.pickedUpAt = System.currentTimeMillis()
        o.status = OrderStatus.DELIVERING
        o.lastDeliveryStatus = "PICKED_UP"
        return orderService.save(o)
    }

    fun telemetry(orderId: String, riderId: String, req: TelemetryRequest): TelemetryReading {
        val o = orders.require(orderId)
        orderService.requireStatus(o, OrderStatus.DELIVERING, OrderStatus.DELIVERY_EXCEPTION)
        if (o.riderId != riderId) throw ForbiddenException("该订单由骑手 ${o.riderId} 配送，$riderId 无权上报")

        val reading = TelemetryReading(
            orderId = orderId,
            riderId = riderId,
            tempC = req.tempC,
            lat = req.lat,
            lng = req.lng,
            boxOpened = req.boxOpened,
            detour = req.detour,
            deliveryStatus = req.deliveryStatus,
            at = System.currentTimeMillis()
        )
        store.rpushJson("telemetry:$orderId", reading)

        o.lastTempC = req.tempC
        req.deliveryStatus?.let { o.lastDeliveryStatus = it }

        if (req.boxOpened) {
            o.boxOpenCount += 1
            if (o.boxOpenCount > MAX_BOX_OPENS) {
                markException(o)
                incidents.report(
                    orderId, IncidentType.BOX_OPENED_ABNORMAL, Role.RIDER, riderId,
                    "保温箱开盖次数 ${o.boxOpenCount} 超过上限 $MAX_BOX_OPENS"
                )
            }
        }

        val range = o.drugType
        if (req.tempC < range.minTempC || req.tempC > range.maxTempC) {
            o.overTemp = true
            markException(o)
            val type = if (req.detour) IncidentType.RIDER_DETOUR_OVERTEMP else IncidentType.OVER_TEMPERATURE
            incidents.report(
                orderId, type, Role.RIDER, riderId,
                "箱内温度 ${req.tempC}°C 超出冷链范围 ${range.minTempC}-${range.maxTempC}°C" +
                    if (req.detour) "（骑手绕路导致）" else ""
            )
        }

        orderService.save(o)
        return reading
    }

    fun deliveryAttempt(orderId: String, riderId: String, req: DeliveryAttemptRequest): ColdChainOrder {
        val o = orders.require(orderId)
        orderService.requireStatus(o, OrderStatus.DELIVERING, OrderStatus.DELIVERY_EXCEPTION)
        if (o.riderId != riderId) throw ForbiddenException("该订单由骑手 ${o.riderId} 配送，$riderId 无权操作")
        when (req.result) {
            "PATIENT_NOT_HOME" -> {
                o.status = OrderStatus.SIGN_FAILED
                o.usability = Usability.PENDING_REJUDGMENT
                o.lastDeliveryStatus = "PATIENT_NOT_HOME"
                orderService.save(o)
                incidents.report(
                    orderId, IncidentType.PATIENT_NOT_HOME, Role.RIDER, riderId,
                    "患者不在家，签收失败，冷藏药品已在途等待药师判断"
                )
            }
            else -> throw BadRequestException("不支持的配送结果: ${req.result}（目前支持 PATIENT_NOT_HOME）")
        }
        return o
    }

    fun telemetryList(orderId: String): List<TelemetryReading> =
        store.lrangeJson("telemetry:$orderId")

    /** 出现异常：订单进入配送异常，药品可用性转为待药师重新判断 */
    private fun markException(o: ColdChainOrder) {
        if (o.status == OrderStatus.DELIVERING) o.status = OrderStatus.DELIVERY_EXCEPTION
        if (o.usability == Usability.USABLE) o.usability = Usability.PENDING_REJUDGMENT
    }
}
