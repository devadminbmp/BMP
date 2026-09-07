package com.bmp.review.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for review_schema.review.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "review", schema = "review_schema")
@Getter
public class Review {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "stylist_id")
    private UUID stylistId;
    @Setter
    @Column(name = "salon_rating", nullable = false)
    private int salonRating;
    /**
     * 1–5, or NULL when the customer rated the salon but not the stylist.
     *
     * <h2>Session 48 — this was a primitive {@code int}, and that was a bug</h2>
     * The column has always been nullable ("mandatory only if stylist assigned"), but a primitive
     * cannot hold null, so {@code ReviewService.create} defaulted a missing rating to <b>0</b> —
     * a value outside the documented range, written silently on every review where the customer
     * skipped the stylist.
     *
     * <p>It stayed invisible because nothing read the field back for display. The moment a stylist
     * could see their own reviews it became a 0-star review nobody left, and an average dragged
     * down by every unrated booking. V005 nulls the existing zeros and adds a CHECK.
     *
     * <p><b>Integer, not int</b> — the type has to be able to say "not rated", because that is a
     * real state the schema allows.
     */
    @Setter
    @Column(name = "stylist_rating")
    private Integer stylistRating;
    @Setter
    @Column(name = "review_text")
    private String reviewText;
    /**
     * Who wrote it. V004 (Session 40).
     *
     * <p>This table had no author. `PUT /reviews/{id}` had no @PreAuthorize and sat behind a
     * public-paths entry written for the GET, so it was editable <b>with no credential at all</b>
     * — and even once that was closed, there was nothing to compare a caller against.
     *
     * <p>Null on rows written before V004. {@code ReviewService.update} refuses those rather than
     * guessing: an unattributable review is one nobody can prove they own.
     */
    @Setter
    @Column(name = "author_user_id")
    private UUID authorUserId;

    @Column(name = "edit_locked_at", nullable = false)
    private Instant editLockedAt;
    @Setter
    @Column(name = "needs_remoderation", nullable = false)
    private boolean needsRemoderation;
    @Column(name = "community_post_id", length = 36)
    private String communityPostId;
    /**
     * Set when moderation upheld a report against this review. V006, Session 60.
     *
     * <h2>Hidden, not deleted</h2>
     * Moderation is reversible; {@code booking_id} is unique so deleting would quietly let the same
     * customer write a replacement for the same appointment; and the rating is history. See V006.
     *
     * <p>No {@code @Setter} — the three hidden columns move together or not at all (there is a
     * CHECK constraint saying so), and an individual setter is an invitation to set one of them.
     * Use {@link #hide} and {@link #unhide}.
     */
    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "hidden_reason", length = 500)
    private String hiddenReason;

    @Column(name = "hidden_by_staff_id")
    private UUID hiddenByStaffId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Review() {} // JPA

    /** Is this review visible to customers? The one question every public read asks. */
    public boolean isHidden() {
        return hiddenAt != null;
    }

    /**
     * Take it off the salon's page. Called only by the internal moderation endpoint.
     *
     * <p>Sets all three columns together — V006's CHECK constraint refuses any other combination,
     * so "hidden with no reason" cannot reach the database and the question "why, and who" always
     * has an answer.
     *
     * <p>Idempotent: upholding an already-upheld report should not overwrite the original reason
     * and moderator with a second one, because the first decision is the one that was appealed
     * against.
     */
    public void hide(String reason, UUID staffId) {
        if (hiddenAt != null) return;
        this.hiddenAt = Instant.now();
        this.hiddenReason = reason;
        this.hiddenByStaffId = staffId;
        this.updatedAt = Instant.now();
    }

    /** Put it back — an appeal succeeded, or the report was upheld in error. */
    public void unhide() {
        this.hiddenAt = null;
        this.hiddenReason = null;
        this.hiddenByStaffId = null;
        this.updatedAt = Instant.now();
    }

    /** @param stylistRating null when the customer didn't rate the stylist — never 0. See V005. */
    public Review(UUID bookingId, UUID salonId, UUID stylistId, int salonRating, Integer stylistRating, String reviewText, Instant editLockedAt, boolean needsRemoderation, String communityPostId) {
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

    public void touch() { this.updatedAt = Instant.now(); }
}
