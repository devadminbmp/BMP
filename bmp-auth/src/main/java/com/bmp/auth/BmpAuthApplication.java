package com.bmp.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * NEW in Session 5. Owns login end-to-end: OTP request/verify, JWT issuance, refresh,
 * logout. Deliberately separate from bmp-user-service (which owns profile data) — calls
 * it over REST (via the Feign client in internal.client) rather than sharing entities,
 * since they're now different deployables.
 *
 * <p>Standard port: 8081.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class BmpAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(BmpAuthApplication.class, args);
    }
}
