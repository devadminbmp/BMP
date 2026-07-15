package com.bmp.review.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for review_schema.salon_rating_snapshot.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_rating_snapshot", schema = "review_schema")
public class SalonRatingSnapshot {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "overall_rating", nullable = false)
    private BigDecimal overallRating;
    @Column(name = "total_reviews", nullable = false)
    private int totalReviews;
    @Column(name = "reviews_last_30_days", nullable = false)
    private int reviewsLast30Days;
    @Column(name = "rating_last_30_days")
    private BigDecimal ratingLast30Days;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonRatingSnapshot() {} // JPA

    public SalonRatingSnapshot(UUID salonId, BigDecimal overallRating, int totalReviews, int reviewsLast30Days, BigDecimal ratingLast30Days) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.overallRating = overallRating;
        this.totalReviews = totalReviews;
        this.reviewsLast30Days = reviewsLast30Days;
        this.ratingLast30Days = ratingLast30Days;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public BigDecimal getOverallRating() { return overallRating; }
    public int getTotalReviews() { return totalReviews; }
    public int getReviewsLast30Days() { return reviewsLast30Days; }
    public BigDecimal getRatingLast30Days() { return ratingLast30Days; }
    public Instant getUpdatedAt() { return updatedAt; }
}
