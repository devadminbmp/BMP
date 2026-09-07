package com.bmp.rewards;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Independently-deployable Spring Boot service (Session 5 microservices pivot — see
 * bmp-app/RETIRED.md for what this replaces). Registers with Eureka on startup as
 * "bmp-rewards-service" (must match this value exactly wherever another service's Feign
 * client or api-gateway route refers to it).
 *
 * <p>Standard port: 8087.
 *
 * <p>{@code @EntityScan}/{@code @ComponentScan} explicitly include {@code com.bmp.common}
 * alongside this service's own package, since {@code OutboxEntry}/{@code OutboxPublisher}
 * and {@code CommonSecurityConfig}/{@code JwtAuthFilter} (shared outbox + JWT security,
 * used by every service) live outside this service's own package tree and Spring Boot's
 * default component/entity scan only covers the main class's package. Without the
 * {@code @ComponentScan}, CommonSecurityConfig is silently never registered and Spring
 * Boot falls back to its own auto-generated-password HTTP Basic security on everything.
 */
@org.springframework.cloud.openfeign.EnableFeignClients
@SpringBootApplication
// Session 47: the outbox→Kafka relay is a @Scheduled poll. Without this the relay bean
// exists and its method never runs — events commit to the outbox and are never published.
@EnableScheduling
@EnableDiscoveryClient
@ComponentScan(basePackages = {"com.bmp.rewards", "com.bmp.common"})
@EntityScan(basePackages = {"com.bmp.rewards", "com.bmp.common"})
@EnableJpaRepositories(basePackages = "com.bmp.rewards")
public class BmpRewardsApplication {
    public static void main(String[] args) {
        SpringApplication.run(BmpRewardsApplication.class, args);
    }
}
