package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.stylist_salon.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "stylist_salon", schema = "salon_schema")
public class StylistSalon {

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "status", nullable = false, length = 10)
    private String status;
    @Column(name = "salon_rating")
    private BigDecimal salonRating;
    @Column(name = "salon_review_count", nullable = false)
    private int salonReviewCount;
    @Column(name = "is_available_today", nullable = false)
    private boolean isAvailableToday;
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;
    @Column(name = "left_at")
    private Instant leftAt;

    protected StylistSalon() {} // JPA

    public StylistSalon(UUID stylistId, UUID salonId, String status, BigDecimal salonRating, int salonReviewCount, boolean isAvailableToday, Instant joinedAt, Instant leftAt) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.salonId = salonId;
        this.status = status;
        this.salonRating = salonRating;
        this.salonReviewCount = salonReviewCount;
        this.isAvailableToday = isAvailableToday;
        this.joinedAt = joinedAt;
        this.leftAt = leftAt;

    }

    public UUID getId() { return id; }
    public UUID getStylistId() { return stylistId; }
    public UUID getSalonId() { return salonId; }
    public String getStatus() { return status; }
    public BigDecimal getSalonRating() { return salonRating; }
    public int getSalonReviewCount() { return salonReviewCount; }
    public boolean isAvailableToday() { return isAvailableToday; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }
}
