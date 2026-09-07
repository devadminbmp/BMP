package com.bmp.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Independently-deployable Spring Boot service (Session 5 microservices pivot — see
 * bmp-app/RETIRED.md for what this replaces). Registers with Eureka on startup as
 * "bmp-payment-service" (must match this value exactly wherever another service's Feign
 * client or api-gateway route refers to it).
 *
 * <p>Standard port: 8085.
 *
 * <p>{@code @EntityScan}/{@code @ComponentScan} explicitly include {@code com.bmp.common}
 * alongside this service's own package, since {@code OutboxEntry}/{@code OutboxPublisher}
 * and {@code CommonSecurityConfig}/{@code JwtAuthFilter} (shared outbox + JWT security,
 * used by every service) live outside this service's own package tree and Spring Boot's
 * default component/entity scan only covers the main class's package. Without the
 * {@code @ComponentScan}, CommonSecurityConfig is silently never registered and Spring
 * Boot falls back to its own auto-generated-password HTTP Basic security on everything.
 */
/*
 * Session 50 — @EnableScheduling is REQUIRED, not decorative.
 *
 * OutboxKafkaRelay drains the transactional outbox on a @Scheduled poll. Without this annotation
 * the bean is created, looks healthy, and its scheduled method is never invoked — so
 * payment.captured events pile up in the outbox table forever and no booking is ever confirmed.
 *
 * Exactly the bug found in bmp-admin in Session 48: an event published into a relay that never
 * runs is indistinguishable from an event that was never published, right up until someone
 * queries the table.
 */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@EnableDiscoveryClient
@ComponentScan(basePackages = {"com.bmp.payment", "com.bmp.common"})
@EntityScan(basePackages = {"com.bmp.payment", "com.bmp.common"})
@EnableJpaRepositories(basePackages = "com.bmp.payment")
public class BmpPaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(BmpPaymentApplication.class, args);
    }
}
