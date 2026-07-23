package com.bmp.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Request/response DTOs for BMP-30 — notification_log CRUD. All non-public nested to keep one file per module. */
public final class NotificationDtos {
    private NotificationDtos() {}

    public record LogRequest(
        @NotNull UUID recipientUserId,
        @NotBlank String channel,       // whatsapp | sms | push | email
        @NotBlank String templateCode,
        Map<String, Object> payload
    ) {}

    public record LogResponse(
        UUID id,
        UUID recipientUserId,
        String channel,
        String templateCode,
        Map<String, Object> payload,
        String status,
        String providerMessageId,
        String errorReason,
        Instant createdAt,
        Instant sentAt,
        Instant deliveredAt
    ) {}

    /** Ported from Session 7 BMP-30 — counts by status, for an ops dashboard view. */
    public record StatsResponse(long queued, long sent, long delivered, long failed, long total) {}

    public record ErrorResponse(String error, String message) {}
}
