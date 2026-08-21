package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_policy.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_policy", schema = "salon_schema")
@Getter
public class SalonPolicy {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Setter
    @Column(name = "template", nullable = false, length = 20)
    private String template;
    @Setter
    @Column(name = "free_cancel_hours", nullable = false)
    private int freeCancelHours;
    @Setter
    @Column(name = "late_grace_minutes", nullable = false)
    private int lateGraceMinutes;
    @Setter
    @Column(name = "require_prepayment", nullable = false)
    private boolean requirePrepayment;
    @Setter
    @Column(name = "slot_granularity_minutes", nullable = false)
    private int slotGranularityMinutes;

    /**
     * Platform commission in BASIS POINTS. 1200 = 12.00%. Added in V009.
     *
     * <p>Basis points, not a percentage or a decimal, for the same reason every money column
     * here is integer paise: this number multiplies someone's income, and a floating-point rate
     * puts rounding drift in the one calculation where it is least acceptable.
     * {@code Money.percentBps()} in bmp-common already takes exactly this.
     *
     * <p>Negotiated per salon. The 1200 default preserves the previously hardcoded rate and is
     * <b>not</b> an agreed commercial number — see V009's header.
     */
    @Setter
    @Column(name = "commission_bps", nullable = false)
    private int commissionBps = 1200;

    // ---- V010: cancellation fee tiers and rescheduling ------------------------------------
    //
    // Until Session 37 `freeCancelHours` was frozen onto every booking and read by nothing:
    // a customer cancelling two minutes before their appointment and one cancelling three
    // weeks out got identical treatment. These columns are what makes the frozen terms mean
    // something.
    //
    // Every fee defaults to ZERO. A migration must never quietly start charging a salon's
    // existing customers — taking money is opt-in, per salon, through the policy screen.

    /**
     * The inner boundary, in hours. Below this the salon has essentially no chance of refilling
     * the slot. A salon wanting a single cut-off sets {@code lateCancelFeeBps} equal to
     * {@code noNoticeFeeBps} and the middle band disappears.
     */
    @Setter
    @Column(name = "late_cancel_hours", nullable = false)
    private int lateCancelHours = 2;

    /** Charged between {@code lateCancelHours} and {@code freeCancelHours}. Basis points. */
    @Setter
    @Column(name = "late_cancel_fee_bps", nullable = false)
    private int lateCancelFeeBps = 0;

    /** Charged below {@code lateCancelHours}. Basis points; 10000 = the full price. */
    @Setter
    @Column(name = "no_notice_fee_bps", nullable = false)
    private int noNoticeFeeBps = 0;

    /**
     * How much notice a CUSTOMER needs to move their own appointment.
     *
     * <p>Defaults to 24h, matching the default free-cancel window — so out of the box "you can
     * still change it" and "you can still cancel free" end at the same moment, which is the
     * easiest version to explain to a customer.
     */
    @Setter
    @Column(name = "reschedule_notice_hours", nullable = false)
    private int rescheduleNoticeHours = 24;

    /**
     * Per BOOKING, not per customer. 0 disables customer rescheduling entirely.
     *
     * <p>A limit is needed because rescheduling is free, and a slot held by a booking that moves
     * every week is a slot nobody else can take.
     */
    @Setter
    @Column(name = "max_reschedules_per_booking", nullable = false)
    private int maxReschedulesPerBooking = 2;

    /**
     * May the salon MOVE a booking, or only ask?
     *
     * <p>False by default, and that is a product decision: a salon silently moving someone's
     * Saturday morning is the kind of thing discovered at the door. True exists because some
     * salons genuinely operate that way — a stylist quits and everything shifts an hour — and
     * forcing forty individual approvals through the app just pushes them to the phone, where
     * BMP has no record at all. Either way the customer is notified; this only decides whether
     * the move waits for a yes.
     */
    @Setter
    @Column(name = "salon_can_reschedule_directly", nullable = false)
    private boolean salonCanRescheduleDirectly = false;

    /**
     * THE LOOPHOLE GUARD. True means the cancellation clock stays pinned to the booking's
     * ORIGINAL appointment time however many times it moves.
     *
     * <p>Without it: book Saturday 11:00 → at 10:00, an hour before and deep inside the fee
     * window, reschedule to next month → the clock now measures against next month → cancel,
     * free. The salon lost Saturday's slot with an hour's notice and was paid nothing.
     *
     * <p>Defaults to true (closed). A salon can open it deliberately, knowing what it means.
     */
    @Setter
    @Column(name = "reschedule_keeps_original_clock", nullable = false)
    private boolean rescheduleKeepsOriginalClock = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonPolicy() {} // JPA

    public SalonPolicy(UUID salonId, String template, int freeCancelHours, int lateGraceMinutes, boolean requirePrepayment, int slotGranularityMinutes) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.template = template;
        this.freeCancelHours = freeCancelHours;
        this.lateGraceMinutes = lateGraceMinutes;
        this.requirePrepayment = requirePrepayment;
        this.slotGranularityMinutes = slotGranularityMinutes;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }
}
