package com.bmp.auth.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Session 6: attaches the shared internal-service credential to every outgoing Feign call
 * this service makes (to bmp-user-service, bmp-salon-service), matching what
 * {@code com.bmp.common.security.JwtAuthFilter} accepts as {@code ROLE_SERVICE}. bmp-auth is
 * calling these services BEFORE any end-user JWT exists yet (it's literally in the middle of
 * creating one), so there's no user token to forward instead.
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
