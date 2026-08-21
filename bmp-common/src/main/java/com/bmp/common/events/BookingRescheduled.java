package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A booking moved to a different time. Session 37.
 *
 * <h2>Why this is the most important of the booking events</h2>
 * The other three tell a customer something they already know — they pressed the button. This
 * one can tell them something they <b>don't</b>: when the salon moved the appointment.
 *
 * <p>A customer who isn't told, and isn't looking at their phone, turns up at the old time. That
 * is a wasted journey caused by the platform, and it is the failure a booking app has least
 * excuse for.
 *
 * <h2>Both old and new times ride along</h2>
 * Not just the new one. "Your appointment is now Tuesday 3pm" is ambiguous to someone with two
 * bookings; "moved from Saturday 11:00 to Tuesday 15:00" is not. The old time is also what makes
 * the message checkable against the customer's own memory, which is the point of a notification
 * about a change.
 *
 * <h2>Rescheduling is not a status transition</h2>
 * The booking stays PENDING or CONFIRMED. This event exists precisely because nothing in
 * {@code booking.status} changes, so a consumer watching statuses would never see it happen.
 *
 * <p>{@code booking_modification} carries the full before/after detail for audit;
 * this event carries only what a message needs. A future consumer wanting the whole picture
 * should read that table rather than growing this record — an event that carries everything is
 * an event nobody can change.
 *
 * @param aggregateId  the booking row id
 * @param bookingRef   human reference
 * @param salonName    snapshotted at booking time; nullable for pre-V006 bookings
 * @param customerId   notification_log recipient
 * @param customerName nullable
 * @param phone        nullable, unmasked, from the V006 snapshot — so publishing costs no
 *                     outbound call even when bmp-user is down
 * @param email        nullable
 * @param previousStart where it was. Nullable only for a booking with no items, which shouldn't
 *                      exist but shouldn't break delivery either.
 * @param newStart     where it is now
 * @param reason       required when the salon moved it, optional when the customer did
 * @param movedBy      "customer" or "salon" — decides whether this is a receipt or real news
 */
public record BookingRescheduled(
        UUID aggregateId,
        String bookingRef,
        String salonName,
        UUID customerId,
        String customerName,
        String phone,
        String email,
        Instant previousStart,
        Instant newStart,
        String reason,
        String movedBy
) implements DomainEvent {

    @Override
    public String eventType() {
        return "booking.rescheduled";
    }
}
