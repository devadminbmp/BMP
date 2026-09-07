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

    /**
     * V011 (Session 40). "Colour specialist", "Bridal" — one line under the name in the picker.
     *
     * <p>The rating and review count already existed ({@code overallRating}, and
     * {@code stylist_salon.salonRating} for this salon specifically). Only this was missing, and
     * it is the field that turns a list of names into a reason to choose one.
     */
    // Plain field + explicit accessor below — this entity doesn't use Lombok.
    @Column(name = "speciality", length = 120)
    private String speciality;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ══ platform suspension. V025 (Session 51). ═══════════════════════════════════════════════

    /**
     * Non-null = barred from BMP entirely.
     *
     * <h2>Not the same thing as being removed from a salon</h2>
     * A salon removing a stylist is employment — the {@code stylist_salon} row becomes alumni and
     * they can work elsewhere tomorrow. Suspension is the platform saying this person should not
     * be working through BMP at all: they cannot be added to any salon, cannot be accepted from a
     * join request, and produce no bookable slots.
     *
     * <p>It lives on the PERSON rather than on a link because a suspended stylist with no current
     * salon must still be suspended when they later ask to join one — and a per-link flag has
     * nowhere to live in that case.
     */
    @Column(name = "suspended_at")
    private Instant suspendedAt;

    /** Shown to the stylist. A bar with no reason is one they cannot contest. */
    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    /** A bmp_staff id. Every admin action here is attributable. */
    @Column(name = "suspended_by_staff_id")
    private UUID suspendedByStaffId;

    /** Kept after reinstatement, so the record shows it happened and ended. */
    @Column(name = "reinstated_at")
    private Instant reinstatedAt;

    /**
     * Currently barred.
     *
     * <h2>Why this is a predicate over TWO columns and not one nullable flag</h2>
     * The first version cleared {@code suspendedAt} on reinstatement. That destroyed the record of
     * when the bar started — and it violated V025's own CHECK, which requires a reinstatement to
     * have a suspension to point at. A test that suspended and then reinstated caught it.
     *
     * <p>Keeping both means the history reads honestly: suspended on the 3rd, reinstated on the
     * 9th, for this reason. Re-suspending later moves {@code suspendedAt} forward and clears
     * {@code reinstatedAt}, so the pair always describes the CURRENT episode.
     */
    public boolean isSuspended() { return suspendedAt != null && reinstatedAt == null; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public String getSuspensionReason() { return suspensionReason; }
    public UUID getSuspendedByStaffId() { return suspendedByStaffId; }
    public Instant getReinstatedAt() { return reinstatedAt; }

    /**
     * Bar this stylist from the platform.
     *
     * <p>Idempotent: suspending an already-suspended stylist keeps the ORIGINAL timestamp and
     * reason. Overwriting them would rewrite when the bar started and why, which is exactly the
     * record somebody will later need to defend or overturn.
     *
     * @param reason required, and at least a few words — see V025's CHECK. Somebody whose
     *               livelihood is affected is owed something they can act on.
     */
    public void suspend(String reason, UUID byStaffId) {
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException(
                    "SUSPENSION_REASON_REQUIRED: say why, in a sentence the stylist can read. "
                    + "An unexplained bar is one they cannot contest and support cannot defend.");
        }
        if (isSuspended()) return;
        this.suspendedAt = Instant.now();
        this.suspensionReason = reason.trim();
        this.suspendedByStaffId = byStaffId;
        // Cleared so this episode starts clean. A stale reinstatedAt from a PREVIOUS suspension
        // would both make isSuspended() false immediately and violate V025's CHECK, which asserts
        // reinstated_at >= suspended_at.
        this.reinstatedAt = null;
    }

    /**
     * Lift the bar.
     *
     * <p>The reason is deliberately NOT cleared. Suspensions get made on incomplete information
     * and sometimes reversed, and a stylist whose record silently returns to pristine leaves
     * nobody able to say what happened. The history reads: suspended for X, reinstated on Y.
     */
    public void reinstate() {
        if (!isSuspended()) return;
        // suspendedAt is KEPT. Clearing it would erase when the bar started and would break
        // V025's CHECK (a reinstatement must have a suspension to point at). isSuspended() reads
        // both columns, so the stylist is unbarred without the history being rewritten.
        this.reinstatedAt = Instant.now();
    }

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

    // V011 (Session 40) — "Colour specialist", "Bridal". The line that turns a list of names
    // into a reason to choose one.
    public String getSpeciality() { return speciality; }
    public void setSpeciality(String speciality) { this.speciality = speciality; }

    /**
     * Session 44 — a stylist's name was previously write-once.
     *
     * <p>{@code StylistController} had exactly one {@code PUT}, for {@code available-today}, so a
     * name typed wrong at invite time stayed wrong forever — on the salon's public page, on every
     * booking row, and on the desk. Salons quick-add stylists mid-shift ("Ravi" for Ravikumar),
     * which makes a typo the normal case rather than the exception.
     *
     * <p>Note this does NOT rewrite history: {@code booking_service_item} keeps its own
     * {@code name_snapshot}, so past bookings still read as they did on the day. Correcting the
     * roster and rewriting the record are different acts, and only the first one is wanted here.
     */
    public void setName(String name) { this.name = name; }
}
