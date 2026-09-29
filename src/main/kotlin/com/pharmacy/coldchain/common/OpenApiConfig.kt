package com.pharmacy.coldchain.common

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.parameters.HeaderParameter
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun coldChainOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("药店冷藏药品配送签收与温控赔付服务 API")
            .version("1.0.0")
            .description(
                "冷藏药品配送签收与温控赔付。所有业务接口通过请求头鉴权：" +
                    "X-User-Id（用户标识）与 X-Role（角色：PHARMACIST/WAREHOUSE/RIDER/PATIENT/" +
                    "CUSTOMER_SERVICE/INSURANCE/COMPENSATION/ADMIN）。" +
                    " 患者不在家处理包含：到达登记（等待时间/电话结果/冷藏代收点）、药师处置意见、" +
                    "改约并重算保温箱可用时长、退回药店任务、药师确认退回箱温与温控重新评估。"
            )
            .license(License().name("内部演示"))
    )

    /** 给所有接口注入两个鉴权请求头，便于在 Swagger UI 中直接调试 */
    @Bean
    fun authHeaderCustomizer(): OperationCustomizer = OperationCustomizer { operation, _ ->
        val names = operation.parameters?.map { it.name }.orEmpty()
        if ("X-User-Id" !in names) {
            operation.addParametersItem(
                HeaderParameter().apply {
                    name = "X-User-Id"
                    description = "用户标识，如 pharmacist-1 / rider-1 / patient-1"
                    required = true
                }
            )
            operation.addParametersItem(
                HeaderParameter().apply {
                    name = "X-Role"
                    description = "PHARMACIST/WAREHOUSE/RIDER/PATIENT/CUSTOMER_SERVICE/INSURANCE/COMPENSATION/ADMIN"
                    required = true
                }
            )
        }
        operation
    }
}
