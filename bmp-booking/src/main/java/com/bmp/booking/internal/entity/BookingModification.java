package com.bmp.booking.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for booking_schema.booking_modification.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "booking_modification", schema = "booking_schema")
public class BookingModification {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "before_snapshot", nullable = false, columnDefinition = "jsonb")
    private String beforeSnapshot;
    @Column(name = "after_snapshot", nullable = false, columnDefinition = "jsonb")
    private String afterSnapshot;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BookingModification() {} // JPA

    public BookingModification(UUID bookingId, String beforeSnapshot, String afterSnapshot) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getBeforeSnapshot() { return beforeSnapshot; }
    public String getAfterSnapshot() { return afterSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
