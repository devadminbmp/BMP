package com.bmp.admin.config;

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
    public OpenAPI bmpAdminOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("BMP Admin Service")
                .version("v1")
                .description("""
                    Internal BMP staff console: bmp_staff (password_hash never appears in a \
                    response), support_ticket, and audit_log (BMP-29). bmp_staff is a \
                    deliberately separate identity space from user_schema.users, per the \
                    Schema Rule in CONTEXT.md — no bmp_staff row is ever a customer/salon \
                    user or vice versa. NOTE: a staff-specific login/JWT-issuance flow \
                    doesn't exist yet — this service currently validates the SAME bearer \
                    token as every other service (bmp-auth's), which is a real gap for a \
                    console this sensitive, not a finished design; flagged rather than \
                    silently left implied."""))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
