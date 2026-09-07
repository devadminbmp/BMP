package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A window in which the salon is shut, overriding its normal opening hours. V019 (Session 48).
 *
 * <h2>An exception, not a rule change</h2>
 * Opening hours say "we open 10–8 on Tuesdays". A closure says "not this Tuesday". Keeping them
 * apart matters: before this existed the only way to shut for a day was to edit or delete the
 * hours, which changes the rule, loses the original, and has to be undone by hand afterwards —
 * and people forget, so the salon stays shut on Tuesdays forever.
 *
 * <h2>Half-open window</h2>
 * {@code [startsAt, endsAt)} — a booking exactly at {@code endsAt} is fine, the salon has
 * reopened. Same convention the slot algorithm uses, so the two agree without anyone having to
 * remember which end is inclusive.
 */
@Entity
@Table(name = "salon_closure", schema = "salon_schema")
@Getter
@NoArgsConstructor
public class SalonClosure {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** "Closed for Diwali". Shown to customers whose booking was affected. Optional. */
    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * Set when the owner reopens. Soft-cancelled rather than deleted: customers were already told
     * about this closure, so we need the row to tell them it is off again — and to explain, later,
     * why a booking was moved.
     */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SalonClosure(UUID salonId, Instant startsAt, Instant endsAt, String reason, UUID createdBy) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** True while this closure still applies. */
    public boolean isActive() {
        return cancelledAt == null;
    }

    /**
     * Does this closure cover the given instant?
     *
     * <p>Start inclusive, end exclusive — see the class javadoc. A cancelled closure covers
     * nothing, so callers never have to remember to filter first.
     */
    public boolean covers(Instant t) {
        return isActive() && !t.isBefore(startsAt) && t.isBefore(endsAt);
    }

    /**
     * Does this closure overlap the window {@code [from, to)}?
     *
     * <p>The standard overlap test, and the one people get wrong: two windows overlap when each
     * starts before the other ends. Writing it as "start is inside, or end is inside" misses the
     * case where the closure completely CONTAINS the window — a whole-day closure versus a
     * one-hour appointment, which is the single most common real case here.
     */
    public boolean overlaps(Instant from, Instant to) {
        return isActive() && startsAt.isBefore(to) && from.isBefore(endsAt);
    }

    public void cancel() {
        if (this.cancelledAt == null) this.cancelledAt = Instant.now();
    }
}
