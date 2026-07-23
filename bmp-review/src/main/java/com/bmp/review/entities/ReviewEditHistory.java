package com.bmp.review.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for review_schema.review_edit_history.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "review_edit_history", schema = "review_schema")
public class ReviewEditHistory {

    @Id
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;
    @Column(name = "version", nullable = false)
    private int version;
    @Column(name = "salon_rating", nullable = false)
    private int salonRating;
    @Column(name = "stylist_rating")
    private int stylistRating;
    @Column(name = "review_text")
    private String reviewText;
    @Column(name = "salon_response_hidden", nullable = false)
    private boolean salonResponseHidden;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewEditHistory() {} // JPA

    public ReviewEditHistory(UUID reviewId, int version, int salonRating, int stylistRating, String reviewText, boolean salonResponseHidden) {
        this.id = UuidV7.generate();
        this.reviewId = reviewId;
        this.version = version;
        this.salonRating = salonRating;
        this.stylistRating = stylistRating;
        this.reviewText = reviewText;
        this.salonResponseHidden = salonResponseHidden;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public int getVersion() { return version; }
    public int getSalonRating() { return salonRating; }
    public int getStylistRating() { return stylistRating; }
    public String getReviewText() { return reviewText; }
    public boolean isSalonResponseHidden() { return salonResponseHidden; }
    public Instant getCreatedAt() { return createdAt; }
}
