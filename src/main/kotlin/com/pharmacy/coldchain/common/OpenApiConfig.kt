package com.pharmacy.coldchain.common

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI 3.x 文档元信息（UI: /swagger-ui.html，JSON: /v3/api-docs） */
@Configuration
class OpenApiConfig {

    @Bean
    fun coldChainOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("药店冷藏药品配送签收与温控赔付服务 API")
            .description(
                "冷藏药品下单审核、出库温控、配送监控、患者签收、患者不在家处理" +
                    "（服务记录/药师意见/改约温控重评/退回药店任务）、异常协同与温控赔付。" +
                    "所有接口通过 X-User-Id / X-Role 请求头鉴权。"
            )
            .version("1.0.0")
    )
}
