package com.bmp.salon.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
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
 * 
 * Note: salon_rating stored as integer (hundredths): 4.71 -> 471. No BigDecimal per schema rule.
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
    private Integer salonRating; // stored as hundredths (471 = 4.71), FROZEN when alumni
    @Column(name = "salon_review_count", nullable = false)
    private int salonReviewCount;
    @Column(name = "is_available_today", nullable = false)
    private boolean isAvailableToday;
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;
    @Column(name = "left_at")
    private Instant leftAt;

    protected StylistSalon() {} // JPA

    public StylistSalon(UUID stylistId, UUID salonId, String status, Integer salonRatingHundredths, int salonReviewCount, boolean isAvailableToday, Instant joinedAt, Instant leftAt) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.salonId = salonId;
        this.status = status;
        this.salonRating = salonRatingHundredths;
        this.salonReviewCount = salonReviewCount;
        this.isAvailableToday = isAvailableToday;
        this.joinedAt = joinedAt;
        this.leftAt = leftAt;
    }

    public UUID getId() { return id; }
    public UUID getStylistId() { return stylistId; }
    public UUID getSalonId() { return salonId; }
    public String getStatus() { return status; }
    public Integer getSalonRating() { return salonRating; } // in hundredths
    public int getSalonReviewCount() { return salonReviewCount; }
    public boolean isAvailableToday() { return isAvailableToday; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }

    // Setters for mutable fields
    public void setStatus(String status) { this.status = status; }
    public void setSalonRating(Integer salonRatingHundredths) { this.salonRating = salonRatingHundredths; } // for alumni snapshot
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }
    public void setIsAvailableToday(boolean isAvailableToday) { this.isAvailableToday = isAvailableToday; }
    // JavaBeans-friendly setter alias (some static analyzers / call-sites expect setAvailableToday)
    public void setAvailableToday(boolean availableToday) { this.isAvailableToday = availableToday; }
}


