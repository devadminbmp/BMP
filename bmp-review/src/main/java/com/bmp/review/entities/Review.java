package com.bmp.review.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for review_schema.review.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "review", schema = "review_schema")
public class Review {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "stylist_id")
    private UUID stylistId;
    @Column(name = "salon_rating", nullable = false)
    private int salonRating;
    @Column(name = "stylist_rating")
    private int stylistRating;
    @Column(name = "review_text")
    private String reviewText;
    @Column(name = "edit_locked_at", nullable = false)
    private Instant editLockedAt;
    @Column(name = "needs_remoderation", nullable = false)
    private boolean needsRemoderation;
    @Column(name = "community_post_id", length = 36)
    private String communityPostId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Review() {} // JPA

    public Review(UUID bookingId, UUID salonId, UUID stylistId, int salonRating, int stylistRating, String reviewText, Instant editLockedAt, boolean needsRemoderation, String communityPostId) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.salonId = salonId;
        this.stylistId = stylistId;
        this.salonRating = salonRating;
        this.stylistRating = stylistRating;
        this.reviewText = reviewText;
        this.editLockedAt = editLockedAt;
        this.needsRemoderation = needsRemoderation;
        this.communityPostId = communityPostId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getSalonId() { return salonId; }
    public UUID getStylistId() { return stylistId; }
    public int getSalonRating() { return salonRating; }
    public int getStylistRating() { return stylistRating; }
    public String getReviewText() { return reviewText; }
    public Instant getEditLockedAt() { return editLockedAt; }
    public boolean isNeedsRemoderation() { return needsRemoderation; }
    public String getCommunityPostId() { return communityPostId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
