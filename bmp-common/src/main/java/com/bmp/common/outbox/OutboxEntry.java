package com.bmp.common.outbox;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * The transactional outbox — LOCKED DECISION replacing Kafka (T2 of the pivot).
 *
 * <p>How it works: a module that emits a domain event writes an OutboxEntry
 * IN THE SAME DATABASE TRANSACTION as its business change. The booking either
 * commits WITH its "booking.completed" event or neither exists — no dual-write
 * problem, no message broker to operate.
 *
 * <p>{@code OutboxProcessor} (bmp-notification module) polls unprocessed rows,
 * dispatches them to registered consumers, and marks them processed. At-least-once
 * delivery: consumers must be idempotent (same rule Kafka would have imposed).
 */
@Entity
@Table(name = "outbox", schema = "common_schema",
       indexes = @Index(name = "idx_outbox_unprocessed", columnList = "processed, created_at"))
public class OutboxEntry {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** Event serialized as JSON. Schema owned by the emitting module's api package. */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEntry() {} // JPA

    public OutboxEntry(String eventType, UUID aggregateId, String payloadJson) {
        this.id = UuidV7.generate();
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payloadJson;
        this.createdAt = Instant.now();
        this.processed = false;
        this.attempts = 0;
    }

    public void markProcessed() {
        this.processed = true;
        this.processedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    }

    public UUID getId()             { return id; }
    public String getEventType()    { return eventType; }
    public UUID getAggregateId()    { return aggregateId; }
    public String getPayload()      { return payload; }
    public Instant getCreatedAt()   { return createdAt; }
    public boolean isProcessed()    { return processed; }
    public int getAttempts()        { return attempts; }
}
