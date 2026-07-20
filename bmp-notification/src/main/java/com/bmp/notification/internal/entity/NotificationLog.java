package com.bmp.notification.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * NotificationLog — One row per send attempt across all channels.
 * This is a stateless log table: the module holds no business state of its own.
 * All real state (bookings, users) lives in the owning module.
 * 
 * Schema: notification_schema | Table: notification_log | V009
 * 
 * Fields locked per CONTEXT.md Module 8: Notification
 * - channel ENUM: whatsapp/sms/push/email
 * - template_code (e.g. OTP_LOGIN, BOOKING_CONFIRMED, REVIEW_PROMPT)
 * - payload JSONB — rendered template variables
 * - status ENUM: queued/sent/delivered/failed
 * - provider_message_id — MSG91/FCM's own message id, for tracing
 * - outbox_entry_id — traces back to the common_schema.outbox row that triggered the send
 */
@Entity
@Table(name = "notification_log", schema = "notification_schema")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationLog {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "channel", nullable = false)
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    @Column(name = "template_code", nullable = false, length = 50)
    private String templateCode;

    @Column(name = "recipient_id", nullable = false, columnDefinition = "UUID")
    private UUID recipientId;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private NotificationStatus status;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "outbox_entry_id", columnDefinition = "UUID")
    private UUID outboxEntryId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UuidV7.generate();
        }
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        if (this.status == null) {
            this.status = NotificationStatus.QUEUED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Notification channels per CONTEXT.md
     */
    public enum NotificationChannel {
        WHATSAPP,
        SMS,
        PUSH,
        EMAIL
    }

    /**
     * Notification status: queued → sent → delivered/failed
     * Per CONTEXT.md: status ENUM: queued/sent/delivered/failed
     */
    public enum NotificationStatus {
        QUEUED,      // Pending send
        SENT,        // Submitted to provider
        DELIVERED,   // Confirmed by provider
        FAILED       // Send attempt failed
    }
}
