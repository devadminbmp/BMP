package com.bmp.salon.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * ⚠️ HIGHEST-PRIORITY DESIGN GAP (T4 of the pivot assessment).
 *
 * <p>This interface is intentionally a stub. The availability model — stylist
 * working hours, breaks, leave, salon hours, slot granularity vs service
 * duration, and WALK-IN BLOCKING — is the hardest and most important data model
 * in the product and it must be designed ON PAPER against 3 real salons' actual
 * weeks BEFORE any table behind this interface is created.
 *
 * <p>The booking module consumes ONLY this interface. Whatever schema wins the
 * paper design, this contract shields booking from it.
 *
 * <p>Design questions the paper exercise must answer (from the pivot doc §T4
 * and open question #6):
 * <ul>
 *   <li>Slot granularity: fixed 15/30-min grid vs duration-derived free slots?</li>
 *   <li>How does a receptionist block a walk-in in &lt; 5 seconds?</li>
 *   <li>Stylist breaks/lunch: recurring template vs per-day exceptions?</li>
 *   <li>Leave and sudden absence with future bookings already taken?</li>
 *   <li>Salon hours vs individual stylist hours — which wins on conflict?</li>
 *   <li>Multi-service bookings spanning slot boundaries?</li>
 * </ul>
 */
public interface AvailabilityApi {

    /** Free, bookable slots for a stylist on a date, given a total service duration. */
    default List<Slot> freeSlots(UUID salonId, UUID stylistId, LocalDate date, int durationMinutes) {
        return freeSlots(salonId, stylistId, date, durationMinutes, null);
    }

    /** "Any available" path: free slots across all active stylists of the salon. */
    default List<Slot> freeSlotsAnyStylist(UUID salonId, LocalDate date, int durationMinutes) {
        return freeSlotsAnyStylist(salonId, date, durationMinutes, null);
    }

    /**
     * Session 37 — the reschedule variant.
     *
     * @param excludeBookingId a booking whose own slots should NOT count as busy. Null for every
     *                         ordinary lookup; set only when that booking is being MOVED.
     *
     * <p>Without it, rescheduling doesn't work for the commonest case: shifting a 60-minute
     * service from 11:00 to 11:30 asks "is 11:30–12:30 free?", and 11:00–12:00 is occupied — by
     * the booking being moved. The customer would be told their own slot is taken.
     *
     * <p>Overloads with a defaulted 4-arg form rather than changing every call site, so the
     * ordinary booking path is untouched and cannot accidentally start excluding something.
     * Everyone else's bookings remain visible either way, so this can never let a reschedule
     * land on another customer.
     */
    List<Slot> freeSlots(UUID salonId, UUID stylistId, LocalDate date, int durationMinutes, UUID excludeBookingId);

    List<Slot> freeSlotsAnyStylist(UUID salonId, LocalDate date, int durationMinutes, UUID excludeBookingId);

    /** The &lt;5-second walk-in block. Called from the salon dashboard quick-add. */
    void blockWalkIn(UUID salonId, UUID stylistId, LocalDate date, LocalTime start, int durationMinutes);

    record Slot(LocalTime start, LocalTime end, UUID stylistId) {}
}
