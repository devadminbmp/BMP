package com.bmp.salon.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Session 8 (availability algorithm): attaches the shared internal-service credential to
 * every Feign call this service makes to bmp-booking-service, matching what
 * com.bmp.common.security.JwtAuthFilter accepts as ROLE_SERVICE. Same pattern as
 * bmp-auth's FeignInternalKeyConfig — kept per-service (not shared in bmp-common) since
 * each service's Feign client needs its own @Configuration reference class.
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
