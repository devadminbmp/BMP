package com.bmp.review.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for review_schema.stylist_rating_snapshot.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "stylist_rating_snapshot", schema = "review_schema")
public class StylistRatingSnapshot {

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;
    @Column(name = "salon_id")
    private UUID salonId;
    @Column(name = "salon_rating")
    private BigDecimal salonRating;
    @Column(name = "overall_rating", nullable = false)
    private BigDecimal overallRating;
    @Column(name = "total_reviews", nullable = false)
    private int totalReviews;
    @Column(name = "qualifies_top_stylist", nullable = false)
    private boolean qualifiesTopStylist;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StylistRatingSnapshot() {} // JPA

    public StylistRatingSnapshot(UUID stylistId, UUID salonId, BigDecimal salonRating, BigDecimal overallRating, int totalReviews, boolean qualifiesTopStylist) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.salonId = salonId;
        this.salonRating = salonRating;
        this.overallRating = overallRating;
        this.totalReviews = totalReviews;
        this.qualifiesTopStylist = qualifiesTopStylist;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getStylistId() { return stylistId; }
    public UUID getSalonId() { return salonId; }
    public BigDecimal getSalonRating() { return salonRating; }
    public BigDecimal getOverallRating() { return overallRating; }
    public int getTotalReviews() { return totalReviews; }
    public boolean isQualifiesTopStylist() { return qualifiesTopStylist; }
    public Instant getUpdatedAt() { return updatedAt; }
}
