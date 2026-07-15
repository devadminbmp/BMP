package com.bmp.booking.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for booking_schema.booking_events.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "booking_events", schema = "booking_schema")
public class BookingEvents {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;
    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;
    @Column(name = "actor_id")
    private UUID actorId;
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BookingEvents() {} // JPA

    public BookingEvents(UUID bookingId, String eventType, String actorType, UUID actorId, String metadata) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.eventType = eventType;
        this.actorType = actorType;
        this.actorId = actorId;
        this.metadata = metadata;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getEventType() { return eventType; }
    public String getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
