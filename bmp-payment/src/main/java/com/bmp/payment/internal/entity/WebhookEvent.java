package com.bmp.payment.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.webhook_event.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "webhook_event", schema = "payment_schema")
public class WebhookEvent {

    @Id
    private UUID id;

    @Column(name = "razorpay_event_id", nullable = false, length = 80)
    private String razorpayEventId;
    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;
    @Column(name = "processed", nullable = false)
    private boolean processed;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEvent() {} // JPA

    public WebhookEvent(String razorpayEventId, String eventType, String rawPayload, boolean processed) {
        this.id = UuidV7.generate();
        this.razorpayEventId = razorpayEventId;
        this.eventType = eventType;
        this.rawPayload = rawPayload;
        this.processed = processed;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getRazorpayEventId() { return razorpayEventId; }
    public String getEventType() { return eventType; }
    public String getRawPayload() { return rawPayload; }
    public boolean isProcessed() { return processed; }
    public Instant getCreatedAt() { return createdAt; }
}
