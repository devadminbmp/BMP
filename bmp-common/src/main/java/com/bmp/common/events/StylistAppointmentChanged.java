package com.bmp.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stylist's own diary changed — something was booked into it, moved, or taken out. Session 53.
 *
 * <h2>The silence this ends</h2>
 * Until now a stylist found out they had been booked by opening the app and looking. Every other
 * party heard immediately: the customer got a confirmation (Session 34) and the salon got an alert
 * (Session 40). The person who actually has to be standing at the chair heard nothing — including
 * for a counter booking a manager took ten minutes before it starts.
 *
 * <h2>Why this is NOT a field on BookingCreated</h2>
 * A booking can span several stylists — a cut with Anjali at 11:00 and a colour with Imran at
 * 11:45 is one booking and two diaries. One event per booking with a list of stylists would make
 * every consumer re-derive "which part is mine", and the notification body differs per person.
 * One event per affected stylist keeps the handler trivial and the message correct.
 *
 * <h2>WHAT THIS DELIBERATELY DOES NOT CARRY</h2>
 * <b>No customer name, phone, email or id. No price.</b>
 *
 * <p>That is the Session 48/49 rule, and it is enforced here rather than in the handler on
 * purpose: a field that does not exist on the record cannot be leaked by a template somebody
 * writes later. A stylist needs to know <i>what, when, and how long</i> — the identity of the
 * customer and the money involved are the salon's, and {@code ScheduleEntryResponse} already
 * withholds both for the same reason.
 *
 * <p>If a stylist genuinely needs to reach a customer, that is the desk's job and goes through the
 * audited reveal-contact endpoint, which writes down who looked.
 *
 * @param aggregateId  the booking. Same id across created / moved / cancelled for one appointment.
 * @param change       {@code booked} | {@code moved} | {@code cancelled}
 * @param serviceNames what they are doing. The only description the stylist gets, so it matters.
 * @param previousStart set only when {@code change} is {@code moved}; null otherwise. Without it
 *                      the message can say the new time but not that anything changed, which is
 *                      the whole point of telling them.
 * @param stylistEmail where to send it. Null means we hold no address for them — logged plainly,
 *                     never guessed. Same convention as every other stylist event.
 */
public record StylistAppointmentChanged(
        UUID aggregateId,
        String bookingRef,
        UUID stylistId,
        UUID salonId,
        String salonName,
        String change,
        Instant startsAt,
        Instant previousStart,
        int durationMinutes,
        List<String> serviceNames,
        String stylistEmail,
        String stylistName
) implements DomainEvent {

    @Override
    public String eventType() {
        return "stylist.appointment.changed";
    }
}
