package com.bmp.monitoring;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * The health/logs/metrics dashboard for every other BMP service. Auto-discovers instances
 * via Eureka — no separate spring-boot-admin-starter-client dependency needed on the
 * services being monitored, since discovery reads the registry directly and calls each
 * instance's already-exposed actuator endpoints (see InternalKeyExchangeFilterConfig for
 * how it authenticates those calls).
 *
 * <p>Standard port: 8090.
 *
 * <p>Session 7 addition.
 */
@SpringBootApplication
@EnableAdminServer
@EnableDiscoveryClient
public class BmpMonitoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(BmpMonitoringApplication.class, args);
    }
}
