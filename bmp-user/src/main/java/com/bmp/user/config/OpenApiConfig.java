package com.bmp.user.config;

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
    public OpenAPI bmpUserOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("BMP User Service")
                .version("v1")
                .description("""
                    users + user_roles + onboarding_state (BMP-22, completed Session 13). \
                    Users are created by bmp-auth-service on first successful OTP verify \
                    (stored verified — every creation path is post-OTP), with a DB-enforced \
                    unique phone. Roles beyond a user's defaultRole are tracked in \
                    user_roles (deduplicated, revocable, salon-scopable); the default role \
                    is switchable when held. Soft deactivation hides an account until the \
                    next OTP login auto-reactivates it. onboarding_state is a transient \
                    crash-recovery blob, deleted on completion.

                    First business service with a real authorization pass: self-or-service \
                    on user-facing endpoints, service-only (X-Internal-Service-Key) for \
                    creation, phone lookup, role grants/revocations and reactivation."""))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
