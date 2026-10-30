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
                        .version("V4")
                        .description("AI 增强校园二手交易平台后端接口；当前 V4 覆盖多用户认证隔离、商品与图片管理及线下订单闭环。"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
