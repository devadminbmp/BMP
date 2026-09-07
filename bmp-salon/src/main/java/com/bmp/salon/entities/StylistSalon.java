package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.stylist_salon.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "stylist_salon", schema = "salon_schema")
@Getter
public class StylistSalon {

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Setter
    @Column(name = "status", nullable = false, length = 10)
    private String status;
    @Setter
    @Column(name = "salon_rating")
    private BigDecimal salonRating;
    @Column(name = "salon_review_count", nullable = false)
    private int salonReviewCount;
    @Column(name = "is_available_today", nullable = false)
    private boolean isAvailableToday;
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;
    @Setter
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

    public void setIsAvailableToday(boolean isAvailableToday) { this.isAvailableToday = isAvailableToday; }

    // ══ employment ════════════════════════════════════════════════════════════════════════════
    //
    // Session 48. The two statuses this column has always documented, now with the transition
    // between them written down instead of left to each caller.

    /** Working here right now. At most one row per stylist may be in this state — see V021. */
    public static final String ACTIVE = "active";

    /**
     * Used to work here. The row is KEPT — it is the stylist's work history, and deleting it is
     * what would destroy the experience this design exists to preserve.
     */
    public static final String ALUMNI = "alumni";

    public boolean isActive() { return ACTIVE.equalsIgnoreCase(status); }

    /**
     * End this stylist's time at this salon.
     *
     * <h2>Why this is not a delete</h2>
     * A stylist who leaves a salon keeps their profile, their rating and their reviews; the only
     * thing that ends is the employment. Removing the row would erase the fact that they ever
     * worked there, which is exactly the history a stylist joining somewhere new wants to show.
     *
     * <p>It also matters for old bookings: a completed booking points at a stylist who worked at
     * that salon on that day, and the row is the only record of that having been true.
     *
     * <h2>Idempotent on purpose</h2>
     * Leaving twice is not an error worth surfacing — an owner tapping "remove" on a stylist who
     * already left should see them gone, not a 409. But the FIRST left_at is kept, because it is
     * the real leaving date and overwriting it with today's would quietly rewrite their history.
     */
    public void leave(Instant when) {
        if (!isActive()) return;
        this.status = ALUMNI;
        if (this.leftAt == null) this.leftAt = (when != null ? when : Instant.now());
        // Somebody who no longer works here cannot be bookable here. Without this the salon's
        // availability could still offer them, and a customer would book a stylist who has left.
        this.isAvailableToday = false;
    }
}
