package com.bmp.rewards.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the shared internal-service credential to every Feign call bmp-rewards makes, so the
 * callee's {@code JwtAuthFilter} sees {@code ROLE_SERVICE}.
 *
 * <p>Same pattern as bmp-admin's, bmp-salon's and bmp-auth's, and kept per-service for the same
 * reason: a Feign client's {@code configuration} attribute needs a concrete {@code @Configuration}
 * class to point at, and sharing one across services would put every service's outbound
 * credentials in one place.
 *
 * <p>bmp-rewards makes exactly one outbound call today — resolving a salon owner's contact
 * details when they raise an offer request, so the notification has somewhere to go.
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
