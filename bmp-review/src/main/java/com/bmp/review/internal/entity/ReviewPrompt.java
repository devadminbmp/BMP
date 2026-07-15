package com.bmp.review.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for review_schema.review_prompt.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "review_prompt", schema = "review_schema")
public class ReviewPrompt {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "send_after", nullable = false)
    private Instant sendAfter;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "channel", nullable = false, length = 10)
    private String channel;
    @Column(name = "sent_at")
    private Instant sentAt;

    protected ReviewPrompt() {} // JPA

    public ReviewPrompt(UUID bookingId, Instant sendAfter, Instant expiresAt, String channel, Instant sentAt) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.sendAfter = sendAfter;
        this.expiresAt = expiresAt;
        this.channel = channel;
        this.sentAt = sentAt;

    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public Instant getSendAfter() { return sendAfter; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getChannel() { return channel; }
    public Instant getSentAt() { return sentAt; }
}
