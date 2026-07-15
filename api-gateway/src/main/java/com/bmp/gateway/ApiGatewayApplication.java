package com.bmp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * The single front door. Every client (customer app, salon dashboard, admin panel)
 * calls THIS service — never a business service directly. Routes are defined in
 * application.yml, one per business/auth service, using Eureka service discovery
 * (lb://bmp-user-service etc.) so routing survives a service moving hosts/restarting.
 *
 * <p>Standard port: 8080 (conventional HTTP "front door" port).
 *
 * <p>Session 5 addition — part of the microservices pivot. See bmp-app/RETIRED.md.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
