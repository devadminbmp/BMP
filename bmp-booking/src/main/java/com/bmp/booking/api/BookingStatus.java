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
 * COMPLETED, CANCELLED, NO_SHOW are terminal. Reschedule is NOT a transition —
 * it mutates scheduled_start/end on a CONFIRMED booking and appends a
 * booking_events row; status stays CONFIRMED.
 */
public enum BookingStatus {

    PENDING, CONFIRMED, ARRIVED, IN_SERVICE, COMPLETED, CANCELLED, NO_SHOW;

    public enum Actor { CUSTOMER, SALON, SYSTEM }

    private record Transition(BookingStatus to, Actor by) {}

    private static final Map<BookingStatus, Set<Transition>> ALLOWED = Map.of(
        PENDING, Set.of(
            new Transition(CONFIRMED, Actor.SYSTEM),   // Razorpay webhook ONLY
            new Transition(CANCELLED, Actor.SYSTEM)    // payment failed / lock expired
        ),
        CONFIRMED, Set.of(
            new Transition(ARRIVED,   Actor.SALON),
            new Transition(CANCELLED, Actor.CUSTOMER), // fee from policy_snapshot
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
