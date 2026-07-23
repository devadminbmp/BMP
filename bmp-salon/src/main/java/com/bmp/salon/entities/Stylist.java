package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.stylist.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "stylist", schema = "salon_schema")
public class Stylist {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "name", nullable = false, length = 120)
    private String name;
    @Column(name = "overall_rating")
    private BigDecimal overallRating;
    @Column(name = "total_reviews", nullable = false)
    private int totalReviews;
    @Column(name = "is_top_stylist", nullable = false)
    private boolean isTopStylist;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Stylist() {} // JPA

    public Stylist(UUID userId, String name, BigDecimal overallRating, int totalReviews, boolean isTopStylist) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.name = name;
        this.overallRating = overallRating;
        this.totalReviews = totalReviews;
        this.isTopStylist = isTopStylist;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public BigDecimal getOverallRating() { return overallRating; }
    public int getTotalReviews() { return totalReviews; }
    public boolean isTopStylist() { return isTopStylist; }
    public Instant getCreatedAt() { return createdAt; }
}
