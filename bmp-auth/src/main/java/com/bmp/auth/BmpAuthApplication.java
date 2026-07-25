package com.bmp.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

/**
 * NEW in Session 5. Owns login end-to-end: OTP request/verify, JWT issuance, refresh,
 * logout. Deliberately separate from bmp-user-service (which owns profile data) — calls
 * it over REST (via the Feign client in the client package) rather than sharing entities,
 * since they're now different deployables.
 *
 * <p>Standard port: 8081.
 *
 * <p>{@code @ComponentScan}/{@code @EntityScan} explicitly include {@code com.bmp.common}
 * alongside this service's own package, since {@code OutboxPublisher}/{@code OutboxEntry}
 * (shared outbox, used by every service) live outside this service's own package tree and
 * Spring Boot's default scan only covers the main class's package.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
// Session 16: @EnableScheduling is REQUIRED for OutboxKafkaRelay's @Scheduled poll to run —
// without it (and bmp.outbox.relay.enabled=true in yml) OTP/registration events written to
// the outbox NEVER reach Kafka/bmp-notification, and no email/SMS ever goes out. Found live:
// first end-to-end frontend login attempt produced an outbox row and silence.
@org.springframework.scheduling.annotation.EnableScheduling
@ComponentScan(basePackages = {"com.bmp.auth", "com.bmp.common"})
@EntityScan(basePackages = {"com.bmp.auth", "com.bmp.common"})
public class BmpAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(BmpAuthApplication.class, args);
    }
}
