package com.bmp.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html, raw spec at /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI bmpNotificationOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("BMP Notification Service")
                .version("v1")
                .description("""
                    notification_log CRUD (BMP-30) plus the real dispatch pipeline added in \
                    Session 6: OutboxKafkaRelay (bmp-common) publishes outbox rows to the \
                    bmp.events Kafka topic, NotificationDispatcher consumes them and calls \
                    EmailSender/SmsSender. Both channels currently log to the console \
                    instead of sending for real (LoggingEmailSender/LoggingSmsSender are \
                    @Primary) — no SMTP or SMS gateway account exists yet. Swap to real \
                    delivery by moving @Primary to SmtpEmailSender / a real SmsSender impl."""))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
