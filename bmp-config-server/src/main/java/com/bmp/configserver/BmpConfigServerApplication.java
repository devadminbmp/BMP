package com.bmp.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Serves every service's hot-reloadable properties from {@code config-repo/} in this same
 * GitHub repo. See this module's pom.xml description for the full picture (GitHub webhook
 * -> /monitor -> Spring Cloud Bus -> every service refreshes).
 *
 * <p>Standard port: 8888 (Spring Cloud Config's conventional default).
 *
 * <p>Session 7 addition.
 */
@SpringBootApplication
@EnableConfigServer
@EnableDiscoveryClient
public class BmpConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(BmpConfigServerApplication.class, args);
    }
}
