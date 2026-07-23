package com.bmp.booking.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for booking_schema.slot_lock.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "slot_lock", schema = "booking_schema")
public class SlotLock {

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;
    @Column(name = "booking_id")
    private UUID bookingId;
    @Column(name = "lock_date", nullable = false)
    private LocalDate lockDate;
    @Column(name = "start_time", nullable = false)
    private String startTime;
    @Column(name = "end_time", nullable = false)
    private String endTime;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "release_reason", length = 20)
    private String releaseReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SlotLock() {} // JPA

    public SlotLock(UUID stylistId, UUID bookingId, LocalDate lockDate, String startTime, String endTime, Instant expiresAt, String releaseReason) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.bookingId = bookingId;
        this.lockDate = lockDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.expiresAt = expiresAt;
        this.releaseReason = releaseReason;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getStylistId() { return stylistId; }
    public UUID getBookingId() { return bookingId; }
    public LocalDate getLockDate() { return lockDate; }
    public String getStartTime() { return startTime; }
    public String getEndTime() { return endTime; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getReleaseReason() { return releaseReason; }
    public Instant getCreatedAt() { return createdAt; }
}
