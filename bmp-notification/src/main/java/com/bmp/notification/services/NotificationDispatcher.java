package com.bmp.notification.services;

import com.bmp.common.events.OtpRequested;
import com.bmp.common.events.UserRegistered;
import com.bmp.common.kafka.KafkaTopics;
import com.bmp.notification.dto.NotificationDtos.LogRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Session 6: the actual consumer side of OutboxKafkaRelay — subscribes to
 * {@code bmp.events}, routes by the {@code eventType} header (set by the relay), and
 * performs the real side effect (email + SMS/WhatsApp send) that NotificationLogService's
 * plain CRUD never did on its own. Every dispatch is logged via NotificationLogService
 * first (so there's always a record, even if the actual send fails) — see the
 * queued -> sent/failed transition on NotificationLog.
 *
 * <p>At-least-once delivery: Kafka can redeliver on consumer restart. Both handlers here are
 * idempotent in the sense that resending the same OTP/welcome message twice is harmless
 * (not a correctness bug) — worth revisiting if a template is ever added where duplicate
 * sends WOULD matter (e.g. a payment receipt).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final ObjectMapper mapper;
    private final NotificationLogService logService;
    private final EmailSender emailSender;
    private final SmsSender smsSender;

    public NotificationDispatcher(ObjectMapper mapper, NotificationLogService logService,
                                   EmailSender emailSender, SmsSender smsSender) {
        this.mapper = mapper;
        this.logService = logService;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
    }

    @KafkaListener(topics = KafkaTopics.EVENTS, groupId = "bmp-notification-service")
    public void onEvent(@Payload String payload, @Header("eventType") String eventType) {
        try {
            switch (eventType) {
                case "otp.requested" -> handleOtpRequested(mapper.readValue(payload, OtpRequested.class));
                case "user.registered" -> handleUserRegistered(mapper.readValue(payload, UserRegistered.class));
                default -> log.debug("ignoring event type {} — no notification consumer registered for it", eventType);
            }
        } catch (Exception e) {
            log.warn("failed to process {} event: {}", eventType, e.getMessage());
        }
    }

    private void handleOtpRequested(OtpRequested event) {
        // notification_log.recipient_user_id is NOT NULL (locked column) but an OTP send —
        // especially for a brand-new signup — happens BEFORE any user row exists. Using the
        // otp_requests row id here instead of a real user id, same "logical ref, no physical
        // FK" convention used everywhere else in this repo. templateCode=otp_code is the
        // tell for anyone reading this table that recipient_user_id isn't a real user here.
        String body = "Your BMP verification code is " + event.code() + ". It expires at " + event.expiresAt() + ".";

        dispatch(event.aggregateId(), "sms", "otp_code", Map.of("phone", event.phone()),
                () -> smsSender.send(event.phone(), body));

        if (event.email() != null) {
            dispatch(event.aggregateId(), "email", "otp_code", Map.of("email", event.email()),
                    () -> emailSender.send(event.email(), "Your BMP verification code", body));
        }
    }

    private void handleUserRegistered(UserRegistered event) {
        String body = "Welcome to Be My Professional! Your account (" + event.role() + ") is ready.";

        if (event.email() != null) {
            dispatch(event.aggregateId(), "email", "welcome", Map.of("email", event.email()),
                    () -> emailSender.send(event.email(), "Welcome to BMP", body));
        }
        dispatch(event.aggregateId(), "sms", "welcome", Map.of("phone", event.phone()),
                () -> smsSender.send(event.phone(), body));
    }

    private void dispatch(UUID recipientUserId, String channel, String templateCode,
                           Map<String, Object> payload, Runnable send) {
        var logged = logService.log(new LogRequest(recipientUserId, channel, templateCode, payload));
        try {
            send.run();
            logService.markSent(logged.id());
        } catch (Exception e) {
            logService.markFailed(logged.id(), e.getMessage());
            log.warn("notification send failed [{} / {}]: {}", channel, templateCode, e.getMessage());
        }
    }
}
