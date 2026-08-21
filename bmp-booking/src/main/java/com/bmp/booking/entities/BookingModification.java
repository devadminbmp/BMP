package com.bmp.booking.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", nullable = false, columnDefinition = "jsonb")
    private String beforeSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", nullable = false, columnDefinition = "jsonb")
    private String afterSnapshot;
    // ---- V007 (Session 37) -----------------------------------------------------------------
    //
    // This table existed from V002 and NOT ONE ROW was ever written to it — rescheduling was
    // described in BookingStatus's javadoc and never built. before/after snapshots alone cannot
    // answer "who moved this, and why?", which is the first question when a customer complains
    // that their appointment changed.

    /** customer | salon. Decides whose reschedule allowance the move counts against. */
    @Column(name = "actor", nullable = false, length = 20)
    private String actor;

    /** The user who did it. Nullable only for moves made by an internal service. */
    @Column(name = "actor_id")
    private UUID actorId;

    /** Free text. Required from a salon, optional from a customer — see BookingService. */
    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BookingModification() {} // JPA

    /**
     * @deprecated Session 37. Records a modification with no actor, which defaults to
     *             {@code 'customer'} — a wrong attribution written silently, on the one table
     *             whose whole purpose is saying who changed what. Kept only because deleting a
     *             public constructor is a separate change; nothing calls it.
     */
    @Deprecated
    public BookingModification(UUID bookingId, String beforeSnapshot, String afterSnapshot) {
        this(bookingId, beforeSnapshot, afterSnapshot, "customer", null, null);
    }

    public BookingModification(UUID bookingId, String beforeSnapshot, String afterSnapshot,
                                String actor, UUID actorId, String reason) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
        this.actor = actor;
        this.actorId = actorId;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getBeforeSnapshot() { return beforeSnapshot; }
    public String getAfterSnapshot() { return afterSnapshot; }
    public String getActor() { return actor; }
    public UUID getActorId() { return actorId; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
