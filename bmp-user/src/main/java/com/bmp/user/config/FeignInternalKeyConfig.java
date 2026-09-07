package com.bmp.user.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the shared internal-service credential to every Feign call this service makes.
 * Session 45 (support tickets).
 *
 * <p>Matches what {@code com.bmp.common.security.JwtAuthFilter} accepts as {@code ROLE_SERVICE}.
 * Same pattern as bmp-salon's and bmp-auth's — kept per-service rather than shared in bmp-common
 * because each service's {@code @FeignClient} needs its own {@code @Configuration} reference
 * class.
 *
 * <p>The default below is the dev key and must be overridden in any real environment via
 * {@code BMP_INTERNAL_SERVICE_KEY}. A shared secret in a config file is a shared secret in the
 * repository.
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
