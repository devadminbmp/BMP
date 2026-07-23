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
                    users + user_roles CRUD (BMP-22). Created by bmp-auth-service on first \
                    successful OTP verify — most of this API is called service-to-service \
                    (X-Internal-Service-Key), not directly by end users through the \
                    gateway. Roles beyond a user's defaultRole are tracked in user_roles, \
                    each optionally scoped to a salon via salonId."""))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
