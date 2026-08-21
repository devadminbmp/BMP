package com.bmp.common.events;

import java.util.UUID;

/**
 * The appointment happened. Session 34.
 *
 * <h2>This is the event the platform gets the most out of</h2>
 * A completion is the only moment BMP has earned the right to ask for something. It is where a
 * review request belongs, where a referral nudge belongs, and where settlement starts once
 * payments exist. Three separate consumers will eventually subscribe to this one event, which is
 * precisely why it is an event rather than a method call from the desk.
 *
 * <p>Today it does one thing: thank the customer and tell them what they were charged.
 *
 * <h2>Review prompts are NOT sent from here yet</h2>
 * bmp-review currently accepts a review from anyone, for anything — it never checks that the
 * reviewer actually attended (see PENDING_WORK S3). Sending "rate your visit" links before that
 * check exists would invite exactly the fake reviews the check is meant to prevent, and would
 * do it at scale, with BMP's name on the invitation. The prompt goes in when the verification
 * does, and this event is what it will hang off.
 *
 * <h2>At-least-once matters more here than elsewhere</h2>
 * Kafka can redeliver on consumer restart. A duplicate OTP is harmless; a duplicate "thanks for
 * visiting" is mildly embarrassing; a duplicate review prompt or referral credit would not be.
 * Whoever adds the second consumer to this event owns making it idempotent — the dispatcher's
 * class javadoc flags the same thing about payment receipts.
 *
 * @param aggregateId  the booking row id
 * @param bookingRef   human reference
 * @param salonId      so a review consumer knows what is being reviewed without a lookup
 * @param salonName    snapshotted; nullable for pre-V006 bookings
 * @param customerId   notification_log recipient
 * @param customerName nullable
 * @param phone        nullable, unmasked
 * @param email        nullable
 * @param amountPaise  what was charged, after discount. Integer paise.
 */
public record BookingCompleted(
        UUID aggregateId,
        String bookingRef,
        UUID salonId,
        String salonName,
        UUID customerId,
        String customerName,
        String phone,
        String email,
        long amountPaise
) implements DomainEvent {

    @Override
    public String eventType() {
        return "booking.completed";
    }
}
