package com.bmp.monitoring;

import de.codecentric.boot.admin.server.web.client.InstanceExchangeFilterFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Every monitored service's actuator endpoints (beyond health/info) require SOME
 * credential now — see com.bmp.common.security.JwtAuthFilter. Spring Boot Admin has no
 * end-user identity to present, so it authenticates as a service using the same
 * X-Internal-Service-Key header every other internal caller uses. Registering a bean of
 * this type is Spring Boot Admin's documented extension point for adding a header to
 * every outbound call it makes to a monitored instance — no property-name guessing.
 */
@Configuration
public class InternalKeyExchangeFilterConfig {

    @Value("${bmp.security.internal-service-key:dev-only-internal-key-change-in-real-environment}")
    private String internalServiceKey;

    @Bean
    public InstanceExchangeFilterFunction internalServiceKeyHeaderFilter() {
        return (instance, request, next) ->
            next.exchange(org.springframework.web.reactive.function.client.ClientRequest
                .from(request)
                .header("X-Internal-Service-Key", internalServiceKey)
                .build());
    }
}
