package com.bmp.notification.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for notification_schema.notification_log.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "notification_log", schema = "notification_schema")
public class NotificationLog {

    @Id
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;
    @Column(name = "channel", nullable = false, length = 10)
    private String channel;
    @Column(name = "template_code", nullable = false, length = 60)
    private String templateCode;
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;
    @Column(name = "status", nullable = false, length = 10)
    private String status;
    @Column(name = "provider_message_id", length = 120)
    private String providerMessageId;
    @Column(name = "error_reason")
    private String errorReason;
    @Column(name = "outbox_entry_id")
    private UUID outboxEntryId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationLog() {} // JPA

    public NotificationLog(UUID recipientUserId, String channel, String templateCode, String payload, String status, String providerMessageId, String errorReason, UUID outboxEntryId, Instant sentAt) {
        this.id = UuidV7.generate();
        this.recipientUserId = recipientUserId;
        this.channel = channel;
        this.templateCode = templateCode;
        this.payload = payload;
        this.status = status;
        this.providerMessageId = providerMessageId;
        this.errorReason = errorReason;
        this.outboxEntryId = outboxEntryId;
        this.sentAt = sentAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public String getChannel() { return channel; }
    public String getTemplateCode() { return templateCode; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getErrorReason() { return errorReason; }
    public UUID getOutboxEntryId() { return outboxEntryId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
