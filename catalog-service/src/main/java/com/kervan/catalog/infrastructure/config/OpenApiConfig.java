package com.kervan.catalog.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) sözleşme meta verisi. API-first yaklaşımın parçası:
 * çalışan servis kendi sözleşmesini {@code /v3/api-docs} ve {@code /swagger-ui.html}
 * üzerinden yayınlar. Böylece tüketiciler (Gateway, frontend) sözleşmeyi görür.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI catalogOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Kervan Catalog Service API")
                .description("Ürün kataloğu — MongoDB üzerinde esnek öznitelikli ürünler")
                .version("v1")
                .license(new License().name("Öğrenme/portföy")));
    }
}
