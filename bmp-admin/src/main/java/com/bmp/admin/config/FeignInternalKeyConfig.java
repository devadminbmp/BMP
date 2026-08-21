package com.bmp.admin.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the shared internal-service credential to every Feign call bmp-admin makes —
 * matching what {@code com.bmp.common.security.JwtAuthFilter} accepts as {@code ROLE_SERVICE}.
 * Same pattern as bmp-salon's and bmp-auth's, kept per-service because each service's Feign
 * client needs its own {@code @Configuration} reference class.
 *
 * <p><b>Note the direction of trust.</b> bmp-admin calls OUT to bmp-user, bmp-salon,
 * bmp-booking and bmp-rewards with this key. Nothing calls INTO the console with it — the
 * admin filter chain in {@code AdminSecurityConfig} deliberately doesn't accept the service key
 * as a way in, because a credential shared by every service is exactly the wrong thing to let
 * through the door of the tool that can read every customer's personal data.
 */
@Configuration
public class FeignInternalKeyConfig {

    @Value("${bmp.security.internal-service-key:dev-only-internal-key-change-in-real-environment}")
    private String internalServiceKey;

    @Bean
    public RequestInterceptor internalServiceKeyInterceptor() {
        return template -> template.header("X-Internal-Service-Key", internalServiceKey);
    }
}
