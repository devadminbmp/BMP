package com.bmp.booking.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Session 10 (wiring the availability algorithm into booking creation): attaches the
 * shared internal-service credential to this service's outbound Feign call to
 * bmp-salon-service's availability endpoints. Same pattern as bmp-auth's and bmp-salon's
 * FeignInternalKeyConfig — bmp-salon's /api/v1/availability/** endpoints are still fully
 * public today (no authorization pass yet, see CommonSecurityConfig's public-paths note),
 * so this header isn't load-bearing YET, but sending it now means nothing breaks the day
 * bmp-salon does get its own authorization pass and starts requiring it.
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
