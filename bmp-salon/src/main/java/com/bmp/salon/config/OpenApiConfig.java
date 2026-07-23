package com.bmp.salon.config;

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
    public OpenAPI bmpSalonOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("BMP Salon Service")
                .version("v1")
                .description("""
                    salon / salon_policy / salon_hours / salon_service (BMP-23), stylist / \
                    stylist_salon / stylist_service (BMP-24, portable stylist identity — \
                    NO delete endpoints, status moves to 'alumni' instead), and \
                    salon_staff / staff_invites (owner + manager onboarding).

                    Creating a salon requires a SALON_OWNER token — the creator \
                    automatically becomes that salon's OWNER. Manager invites are \
                    owner-only and consumed internally by bmp-auth during MANAGER signup."""))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
