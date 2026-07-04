package com.reconai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reconAiOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ReconAI Engine API")
                .version("0.1.0")
                .description("Transaction reconciliation platform REST API"));
    }
}
