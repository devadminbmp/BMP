package com.bmp.payment.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html, raw spec at /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI bmpPaymentOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("BMP Payment Service")
                .version("v1")
                .description("""
                    payment_order CRUD (BMP-26) — data model and 12% commission split only, \
                    no real Razorpay/payment-gateway integration yet. The manual \
                    status-transition endpoint is feature-flagged \
                    (bmp.payment.allow-manual-status) and meant for dev/testing only — \
                    don't enable it anywhere real money moves."""))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
