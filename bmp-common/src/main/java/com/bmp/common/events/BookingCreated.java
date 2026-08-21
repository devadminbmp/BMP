package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer has booked an appointment.
 *
 * <h2>Session 34 — why this did not exist until now</h2>
 * {@code OutboxPublisher}'s own usage example, written in Session 3, is
 * {@code outbox.publish(new BookingCompleted(...))}. The example event was never written. Every
 * piece of plumbing was in place — the outbox table in bmp-booking's schema, {@code @EntityScan}
 * over {@code com.bmp.common} with a comment explaining it was there for {@code OutboxPublisher},
 * the relay draining to Kafka — and bmp-booking never injected the publisher.
 *
 * <p>The reason it went unnoticed for eight sessions is worth recording, because the same trap
 * exists elsewhere: {@code BookingService.create} already contained the line
 * <pre>recordEvent(booking.getId(), "CREATED", "customer", req.customerId(), Map.of());</pre>
 * That writes to {@code booking_events} — bmp-booking's own append-only audit trail. It is the
 * right thing and it does its job, but it <em>reads</em> like publishing. Two different systems
 * with "event" in the name, one local and one cross-service, and the local one was written
 * first. Nothing failed; a missing event produces silence, not an exception.
 *
 * <p>The consequence was that a customer could book an appointment and receive nothing at all —
 * no confirmation, no reminder. The only message BMP had ever sent them was their login OTP.
 *
 * <h2>Why the contact details ride along</h2>
 * The rule already in force (see bmp-rewards' {@code UserServiceClient}): the dispatcher holds
 * no clients, so whoever emits an event puts a real address in it. bmp-booking resolves the
 * customer once, at booking time, and stores it on the row — so the cancellation and completion
 * events cost no extra call at all.
 *
 * <p>{@code phone} and {@code email} may BOTH be null: bmp-user can be unreachable at booking
 * time, and Session 34's rule is that the booking still succeeds. The dispatcher logs that case
 * loudly rather than pretending a message went out.
 *
 * <h2>Status is PENDING, not CONFIRMED</h2>
 * Bookings sit in {@code PENDING} until the Razorpay webhook confirms them (Phase 3, unbuilt).
 * The message this event triggers must therefore say "requested", not "confirmed" — telling a
 * customer their appointment is confirmed before anyone has taken payment is a promise the
 * platform has not made. A separate {@code booking.confirmed} event belongs with the webhook.
 *
 * @param aggregateId  the booking row id
 * @param bookingRef   human reference, e.g. BMP-2026-00042 — what a customer quotes on the phone
 * @param salonId      logical ref into salon_schema
 * @param salonName    snapshotted at booking time; null only for a booking made while bmp-salon
 *                     was unreachable
 * @param customerId   logical ref into user_schema; the notification_log recipient
 * @param customerName nullable — used to open the message by name when present
 * @param phone        nullable, unmasked, for SMS
 * @param email        nullable — the only channel that works today, since SMS is behind DLT
 * @param firstStart   when the first service starts. The single most useful fact in the message;
 *                     a customer checks the time far more often than the price.
 * @param serviceNames what was booked, in order. Plural because one booking can be a cut AND a
 *                     colour with two stylists — sending only the first would be wrong on
 *                     exactly the bookings that matter most.
 * @param amountPaise  what they will pay, AFTER any discount. Integer paise, never a float.
 * @param salonNotifyEmail Session 40. Where the SALON wants to be told. Nullable.
 * @param salonNotifyPhone same, for SMS. Nullable.
 *
 * <h2>Session 40 — the salon was never told</h2>
 * This event reached the customer and nobody else. The only way a salon learned a customer was
 * coming was by having the desk open, which polls every 60 seconds — so a booking made overnight,
 * or while the tablet was shut, was invisible until somebody looked. For a salon that is the
 * difference between a prepared morning and a surprise, and it is the single most basic thing a
 * booking platform owes its supply side.
 *
 * <p>The salon's contact rides along rather than being looked up at send time, following the same
 * rule as the customer's: the dispatcher holds no clients, so the emitter carries the addresses.
 * Both nullable — a salon that hasn't set them gets no alert, logged plainly, and the booking is
 * unaffected.
 */
public record BookingCreated(
        UUID aggregateId,
        String bookingRef,
        UUID salonId,
        String salonName,
        UUID customerId,
        String customerName,
        String phone,
        String email,
        Instant firstStart,
        java.util.List<String> serviceNames,
        long amountPaise,
        String salonNotifyEmail,
        String salonNotifyPhone
) implements DomainEvent {

    @Override
    public String eventType() {
        return "booking.created";
    }
}
