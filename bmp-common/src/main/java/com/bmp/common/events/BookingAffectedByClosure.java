package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A salon closed over a window that already had this booking in it. Session 48.
 *
 * <h2>Why this is separate from a cancellation</h2>
 * Nothing has happened to the booking yet, and that is the point. The closure blocks NEW bookings
 * immediately, but the existing ones are a decision — reschedule or refund — that the owner makes
 * one at a time, possibly over the next day.
 *
 * <p>Between the closure being recorded and the owner working through the list, the customer is
 * holding an appointment at a salon that will be shut. Without this event they find out by
 * arriving. The email is deliberately a HEADS-UP rather than an outcome: "the salon is closing
 * then, they'll be in touch", not "your booking is cancelled" — because it usually isn't, and
 * telling somebody their appointment is gone when it is about to be moved is its own harm.
 *
 * <p>The real outcome still produces the ordinary booking.cancelled or booking.rescheduled email
 * when the owner acts. This one only closes the gap in between.
 *
 * @param aggregateId    the BOOKING id — this is a fact about a booking, not about the closure
 * @param bookingRef     what the customer quotes back
 * @param salonName      whose salon is closing
 * @param serviceStart   when they were expecting to be seen
 * @param closureStartsAt / closureEndsAt  the shut window, so the email can say how long
 * @param reason         the salon's own words, or null — a salon may shut without explaining
 * @param customerEmail  null means we hold no address; logged rather than guessed
 * @param customerName   for the greeting; null falls back to neutral
 */
public record BookingAffectedByClosure(
        UUID aggregateId,
        String bookingRef,
        String salonName,
        Instant serviceStart,
        Instant closureStartsAt,
        Instant closureEndsAt,
        String reason,
        String customerEmail,
        String customerName
) implements DomainEvent {

    @Override
    public String eventType() {
        return "booking.affected_by_closure";
    }
}
