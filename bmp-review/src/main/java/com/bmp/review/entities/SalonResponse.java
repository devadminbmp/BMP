package com.bmp.review.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for review_schema.salon_response.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_response", schema = "review_schema")
public class SalonResponse {

    @Id
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "response_text", nullable = false)
    private String responseText;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonResponse() {} // JPA

    public SalonResponse(UUID reviewId, UUID salonId, String responseText) {
        this.id = UuidV7.generate();
        this.reviewId = reviewId;
        this.salonId = salonId;
        this.responseText = responseText;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public UUID getSalonId() { return salonId; }
    public String getResponseText() { return responseText; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
