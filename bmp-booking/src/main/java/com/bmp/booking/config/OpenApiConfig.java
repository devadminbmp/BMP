package com.bmp.booking.config;

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
    public OpenAPI bmpBookingOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("BMP Booking Service")
                .version("v1")
                .description("""
                    booking + booking_service_item CRUD, append-only booking_events \
                    (BMP-25). No per-endpoint authorization pass yet (Session 6 scope was \
                    auth/user/salon onboarding) — every endpoint here still accepts any \
                    valid token, ownership of a specific booking isn't checked. Treat as \
                    open until that follow-up ticket lands."""))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
