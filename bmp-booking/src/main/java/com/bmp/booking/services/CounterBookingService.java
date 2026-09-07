package com.bmp.booking.services;

import com.bmp.booking.client.SalonAvailabilityClient;
import com.bmp.booking.dto.BookingDtos.BookingResponse;
import com.bmp.booking.dto.BookingDtos.ItemRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Taking a booking at the counter or over the phone. Session 52.
 *
 * <h2>Darshan's requirement</h2>
 * <i>"Suppose any customer calls the manager or comes to walk in, then the manager should update
 * it in the portal and the manager should select the stylist. One thing to remember — we should
 * compulsorily have their data in our database who are booking through the salon. Remember, it's
 * their own customer."</i>
 *
 * <p>Before this, the only tool the desk had was {@code POST /availability/walk-in}, which writes
 * a {@code walk_in_block}: the stylist's time disappears from the calendar and nothing else is
 * recorded. No customer, no services, no price, no invoice, no history. For most salons that is
 * the majority of their trade.
 *
 * <h2>Why the orchestration is here and not in the controller</h2>
 * Three steps have to happen in a specific order, and the order is the correctness argument:
 *
 * <ol>
 *   <li><b>Upsert the customer FIRST</b>, in bmp-salon. The booking cannot be written without a
 *       {@code salonCustomerId}, so this is what makes "we must have their data" an invariant
 *       rather than a rule somebody remembers. If bmp-salon is down, no booking is taken — a
 *       deliberate refusal, because an anonymous counter booking is the thing being replaced.</li>
 *   <li><b>Write the booking</b>, through the same {@code createInternal} an app booking uses —
 *       same price resolution, same slot validation, same policy snapshot, same invoice.</li>
 *   <li><b>Count the visit LAST</b>, after the booking is committed. Counting first would inflate
 *       a customer's visit count every time a receptionist started a booking and abandoned it.</li>
 * </ol>
 *
 * <p>Step 3 is the only one allowed to fail quietly: the appointment is real and the bill is
 * raised whether or not a loyalty counter incremented.
 */
@Service
public class CounterBookingService {

    private static final Logger log = LoggerFactory.getLogger(CounterBookingService.class);

    private final BookingService bookings;
    private final SalonAvailabilityClient salon;

    public CounterBookingService(BookingService bookings, SalonAvailabilityClient salon) {
        this.bookings = bookings;
        this.salon = salon;
    }

    /**
     * @param salonId        from the MANAGER'S TOKEN, never from the request body. A salonId a
     *                       caller could supply would let one salon write bookings — and contact
     *                       records — into another's diary.
     * @param takenByStaffId the manager's own user id, recorded on the booking so a disputed
     *                       quote can be traced to whoever took it.
     */
    public BookingResponse take(UUID salonId, List<ItemRequest> items,
                                 String name, String phone, String email, String notes,
                                 UUID takenByStaffId) {

        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add at least one service to the booking.");
        }

        // ── 1. the customer record, before anything else ────────────────────────────────────
        SalonAvailabilityClient.SalonCustomer customer;
        try {
            customer = salon.upsertSalonCustomer(salonId,
                    new SalonAvailabilityClient.UpsertSalonCustomer(name, phone, email, notes));
        } catch (feign.FeignException e) {
            if (e.status() == HttpStatus.BAD_REQUEST.value()) {
                // bmp-salon rejected the name or the phone shape, with a message written for the
                // person at the desk ("that doesn't look like a phone number"). Pass it through —
                // a generic 503 here would have the receptionist retrying a typo forever.
                log.info("Counter booking refused at salon {}: {}", salonId, e.contentUTF8());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, extract(e.contentUTF8()));
            }
            log.error("Could not record the customer for a counter booking at salon {} ({}).",
                    salonId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't save the customer's details, so nothing has been booked. "
                    + "Please try again in a moment.");
        } catch (Exception e) {
            log.error("Could not record the customer for a counter booking at salon {} ({}).",
                    salonId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't save the customer's details, so nothing has been booked. "
                    + "Please try again in a moment.");
        }

        // ── 2. the booking itself, down the shared path ─────────────────────────────────────
        BookingResponse booking = bookings.createCounter(salonId, items, customer.id(),
                customer.name(), customer.phone(), customer.email(), takenByStaffId);

        // ── 3. count the visit; never fail the booking for it ───────────────────────────────
        try {
            salon.recordSalonCustomerVisit(salonId, customer.id());
        } catch (Exception e) {
            log.warn("Counter booking {} was taken but the visit count for customer {} was not "
                    + "incremented ({}). The booking is fine; only the 'regular' badge is stale.",
                    booking.bookingRef(), customer.id(), e.toString());
        }

        log.info("Counter booking {} taken at salon {} for {} (visit {}), by staff {}.",
                booking.bookingRef(), salonId, customer.phone(), customer.visitCount() + 1,
                takenByStaffId);
        return booking;
    }

    /**
     * Pulls the human sentence out of a Spring error body so it can be shown at the desk.
     * Falls back to the whole body — an ugly message the receptionist can read out to support
     * beats a polished one that says nothing.
     */
    private static String extract(String body) {
        if (body == null || body.isBlank()) return "We couldn't save those details.";
        int i = body.indexOf("\"message\":\"");
        if (i < 0) return body;
        int start = i + 11;
        int end = body.indexOf('"', start);
        return end < 0 ? body : body.substring(start, end);
    }
}
