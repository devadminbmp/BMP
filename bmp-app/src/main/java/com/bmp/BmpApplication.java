package com.bmp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The ONE deployable. All 8 business modules compile into this single Spring Boot
 * jar. Spring Modulith detects each direct sub-package of {@code com.bmp} as an
 * application module and enforces the boundaries declared in each module's
 * package-info (verified by ModularityTests — the build FAILS on a violation).
 *
 * <p>Extraction path (when evidence demands it): a module's api interfaces become
 * a REST client, its schema moves to its own database, its outbox events move to a
 * real queue. Weeks, not a rewrite.
 */
@SpringBootApplication
@Modulithic(systemName = "BMP")
@EnableScheduling // outbox processor
public class BmpApplication {

    public static void main(String[] args) {
        SpringApplication.run(BmpApplication.class, args);
    }
}
