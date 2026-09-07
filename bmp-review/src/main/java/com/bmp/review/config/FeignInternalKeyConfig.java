package com.bmp.review.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the shared internal-service credential to bmp-review's outbound calls. Session 54.
 *
 * <p>Same pattern as bmp-booking's, bmp-salon's and bmp-auth's. Unlike the earliest of those, this
 * header IS load-bearing from day one: the endpoint it reaches
 * ({@code /api/v1/bookings/internal/**}) is {@code hasRole('SERVICE')} at class level, and that
 * role is granted only by this key.
 *
 * <p>The default value is a development placeholder and must be overridden in any real
 * environment — it is the credential that lets one service act as another.
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
