package com.bmp.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Standalone service registry — the ONE thing every other BMP service depends on at
 * startup. Nothing else runs here: no business logic, no database connection.
 *
 * <p>Standard port: 8761 (Netflix Eureka's well-known default).
 *
 * <p>Session 5 addition — part of the microservices pivot away from the modular
 * monolith. See bmp-app/RETIRED.md and CONTEXT.md Session 5 log for why.
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
