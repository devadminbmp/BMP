package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A booking was cancelled. Session 34.
 *
 * <h2>Why a cancellation needs a message at all</h2>
 * It is tempting to think the customer already knows — they pressed the button. Two reasons that
 * is wrong:
 *
 * <ul>
 *   <li><b>It is the receipt.</b> "You cancelled BMP-2026-00042 for Saturday 11:00" is the thing
 *       a customer can point at when a salon later claims they never cancelled. Without it the
 *       only record is inside BMP's database, which is exactly the record a disputing party
 *       does not accept.</li>
 *   <li><b>{@code cancelledBy} will not always be the customer.</b> Today only a customer can
 *       cancel — it is the sole CUSTOMER-actor transition in {@code BookingStatus}. When
 *       salon-initiated cancellation is modelled, this event already carries the field, and the
 *       message flips from a receipt to genuine news. Adding the field later would mean a schema
 *       change on a live topic; adding it now costs one line.</li>
 * </ul>
 *
 * <h2>Refunds are not mentioned here</h2>
 * Deliberately. What a customer gets back depends on {@code policy_snapshot} and on a payment
 * that does not exist yet (Phase 3). Composing a refund sentence from a service that has never
 * seen a payment would produce a confident number that turns out to be wrong — and a wrong
 * refund figure in writing is worse than no figure. The refund message belongs to bmp-payment,
 * when there is one.
 *
 * @param aggregateId  the booking row id
 * @param bookingRef   human reference
 * @param salonName    snapshotted; nullable for pre-V006 bookings
 * @param customerId   notification_log recipient
 * @param customerName nullable
 * @param phone        nullable, unmasked
 * @param email        nullable
 * @param firstStart   the appointment that is no longer happening — nullable, since a booking
 *                     with no items is possible in principle and should not break delivery
 * @param reason       free text from the customer; nullable, and usually is
 * @param cancelledBy  "customer" today. See above.
 */
public record BookingCancelled(
        UUID aggregateId,
        String bookingRef,
        String salonName,
        UUID customerId,
        String customerName,
        String phone,
        String email,
        Instant firstStart,
        String reason,
        String cancelledBy
) implements DomainEvent {

    @Override
    public String eventType() {
        return "booking.cancelled";
    }
}
