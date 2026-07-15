package com.bmp.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Independently-deployable Spring Boot service (Session 5 microservices pivot — see
 * bmp-app/RETIRED.md for what this replaces). Registers with Eureka on startup as
 * "bmp-admin-service" (must match this value exactly wherever another service's Feign
 * client or api-gateway route refers to it).
 *
 * <p>Standard port: 8088.
 *
 * <p>{@code @EntityScan} explicitly includes {@code com.bmp.common} alongside this
 * service's own package, since {@code OutboxEntry} (shared outbox table, used by every
 * service via bmp-common's OutboxPublisher) lives outside this service's own package tree
 * and Spring Boot's default component/entity scan only covers the main class's package.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EntityScan(basePackages = {"com.bmp.admin", "com.bmp.common"})
@EnableJpaRepositories(basePackages = "com.bmp.admin")
public class BmpAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(BmpAdminApplication.class, args);
    }
}
