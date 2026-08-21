package com.bmp.booking.api;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * LOCKED DECISION: the explicit booking state machine, including WHO may trigger
 * each transition. Any status change outside this table is a bug, full stop.
 *
 * <pre>
 * PENDING ──payment.success (SYSTEM)──▶ CONFIRMED
 * PENDING ──payment failed/expired (SYSTEM)──▶ CANCELLED
 * CONFIRMED ──mark arrived (SALON)──▶ ARRIVED
 * CONFIRMED ──cancel (CUSTOMER, fee per policy_snapshot)──▶ CANCELLED
 * CONFIRMED ──no-show flow, after grace (SALON)──▶ NO_SHOW
 * ARRIVED ──start service (SALON)──▶ IN_SERVICE
 * IN_SERVICE ──mark done (SALON)──▶ COMPLETED   → emits booking.completed
 * </pre>
 *
 * COMPLETED, CANCELLED, NO_SHOW are terminal.
 *
 * <h2>Reschedule is NOT a transition</h2>
 * It moves {@code service_start}/{@code service_end} on {@code booking_service_item} and appends
 * a {@code booking_modification} row plus a {@code booking_events} row. The status does not
 * change.
 *
 * <p>Corrected in Session 37: this paragraph previously said reschedule "mutates
 * scheduled_start/end on a CONFIRMED booking". There are no such columns on {@code booking} —
 * the times have always lived on the item. The comment described a design nobody had built, and
 * described it wrongly, for thirty sessions. Worth remembering that prose in this repo has been
 * wrong more often than the code has.
 *
 * <p>Also corrected: rescheduling is allowed from PENDING as well as CONFIRMED. Since payments
 * do not exist yet, every real booking is PENDING — a reschedule restricted to CONFIRMED would
 * have been unreachable code shipped as a feature.
 */
public enum BookingStatus {

    PENDING, CONFIRMED, ARRIVED, IN_SERVICE, COMPLETED, CANCELLED, NO_SHOW;

    public enum Actor { CUSTOMER, SALON, SYSTEM }

    private record Transition(BookingStatus to, Actor by) {}

    private static final Map<BookingStatus, Set<Transition>> ALLOWED = Map.of(
        PENDING, Set.of(
            new Transition(CONFIRMED, Actor.SYSTEM),   // Razorpay webhook ONLY
            new Transition(CANCELLED, Actor.SYSTEM),   // payment failed / lock expired
            new Transition(CANCELLED, Actor.CUSTOMER), // customer cancels before payment completes (BMP-25)
            new Transition(CANCELLED, Actor.SALON)     // Session 37 — see below
        ),
        CONFIRMED, Set.of(
            new Transition(ARRIVED,   Actor.SALON),
            new Transition(CANCELLED, Actor.CUSTOMER), // fee from policy_snapshot
            /*
             * SESSION 37 — the salon can cancel.
             *
             * This was previously refused, on the reasoning that "the salon cancels on the
             * customer's behalf is not a modelled move". That reasoning holds for a salon
             * cancelling as a FAVOUR — that should stay the customer's decision.
             *
             * It does not hold for the case that actually happens: a burst pipe, a stylist who
             * quits on Friday, a power cut. The salon cannot serve the appointment, and refusing
             * to model that doesn't stop it — it just means the salon rings the customer, tells
             * them not to come, and BMP's database still shows a live booking that then becomes
             * a no-show against a customer who did nothing wrong.
             *
             * A wall with no gate makes people route around it.
             *
             * The rule that makes this safe is in CancellationTerms: a SALON-actor cancellation
             * is ALWAYS fee-free, checked before any policy band, so no combination of settings
             * can charge a customer for the salon's own problem. bmp-booking also requires a
             * reason, which the customer is shown.
             */
            new Transition(CANCELLED, Actor.SALON),
            new Transition(NO_SHOW,   Actor.SALON)     // only after grace period
        ),
        ARRIVED, Set.of(
            new Transition(IN_SERVICE, Actor.SALON)
        ),
        IN_SERVICE, Set.of(
            new Transition(COMPLETED, Actor.SALON)
        ),
        COMPLETED, Set.of(),
        CANCELLED, Set.of(),
        NO_SHOW,   Set.of()
    );

    public boolean canTransitionTo(BookingStatus target, Actor actor) {
        return ALLOWED.get(this).stream()
                .anyMatch(t -> t.to() == target && t.by() == actor);
    }

    public void assertTransition(BookingStatus target, Actor actor) {
        if (!canTransitionTo(target, actor)) {
            throw new IllegalStateException(
                "Illegal booking transition %s -> %s by %s".formatted(this, target, actor));
        }
    }

    public boolean isTerminal() {
        return EnumSet.of(COMPLETED, CANCELLED, NO_SHOW).contains(this);
    }
}
