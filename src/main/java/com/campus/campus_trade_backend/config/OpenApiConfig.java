package com.campus.campus_trade_backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI campusTradeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CampusTrade API")
                        .version("V5")
                        .description("AI 增强校园二手交易平台后端接口；V5 覆盖卖家确认式订单、面交与快递履约、订单详情、站内私信和商品收藏。"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
