package com.bmp.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at {@code /swagger-ui.html}, raw spec at {@code /v3/api-docs}. Reachable with
 * no token — see application.yml's {@code bmp.security.public-paths}, which already
 * includes {@code /swagger-ui/**} and {@code /v3/api-docs/**} for this exact reason.
 *
 * <p>bmp-auth's own endpoints (/api/v1/auth/**) don't require the "Authorize" JWT button to
 * try in Swagger UI — they're what ISSUES the token. The bearer scheme here exists so this
 * service's spec composes correctly if it's ever aggregated behind api-gateway's docs, and
 * so anyone copy-pasting from this file into another service's OpenApiConfig gets the same
 * scheme name (`bearerAuth`) everywhere.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI bmpAuthOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("BMP Auth Service")
                .version("v1")
                .description("""
                    OTP-based authentication (dual-channel: email + phone) and JWT issuance \
                    for all BMP roles — CUSTOMER, SALON_OWNER, MANAGER, STYLIST. Also \
                    handles Google sign-in for customers.

                    Login/signup itself needs no token (that's the point) — everything else \
                    on this service does. There is no password/forgot-password flow: OTP \
                    login IS the recovery flow.""")
                .contact(new Contact().name("BMP Engineering")))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Paste the accessToken from /otp/verify or /oauth2/google here.")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
