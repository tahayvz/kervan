package com.kervan.order.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI orderServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Kervan Order Service")
                .version("v1")
                .description("Sipariş oluşturma ve sorgulama. Sipariş olayları "
                        + "Transactional Outbox üzerinden Kafka'ya yayınlanır."));
    }
}
