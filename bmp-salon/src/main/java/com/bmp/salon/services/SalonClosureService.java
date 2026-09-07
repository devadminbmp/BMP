package com.bmp.salon.services;

import com.bmp.salon.entities.SalonClosure;
import com.bmp.salon.repositories.SalonClosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Holidays and short closures. V019 (Session 48).
 *
 * <h2>What this service refuses to do</h2>
 * It does NOT cancel bookings. Recording "we are shut on Friday" and destroying four customers'
 * appointments are different acts, and only the first is what the owner pressed a button for. The
 * closure blocks NEW bookings immediately (see AvailabilityService.closureWindows); the existing
 * ones are surfaced so the owner can reschedule or refund each, which is the answer they'd want
 * anyway — most customers would rather move than lose the slot.
 *
 * <p>A closure that silently cancelled bookings would also be unrecoverable: undo the closure and
 * the appointments are still gone.
 */
@Service
public class SalonClosureService {

    private static final Logger log = LoggerFactory.getLogger(SalonClosureService.class);

    /**
     * How far ahead a closure may be scheduled.
     *
     * <p>Not a technical limit — a guard against the fat-finger year. Typing 2027 instead of 2026
     * creates a closure nobody notices for twelve months, and its only visible effect is that the
     * salon mysteriously has no availability on one day next year.
     */
    private static final int MAX_DAYS_AHEAD = 400;

    private final SalonClosureRepository closures;
    /** For "who is already booked in this window". Session 48. */
    private final com.bmp.salon.client.BookingServiceClient bookingClient;
    private final com.bmp.salon.repositories.SalonRepository salons;
    /** So stranded customers hear about the closure before they arrive at it. Session 48. */
    private final com.bmp.common.outbox.OutboxPublisher outbox;

    public SalonClosureService(SalonClosureRepository closures,
                                com.bmp.salon.client.BookingServiceClient bookingClient,
                                com.bmp.salon.repositories.SalonRepository salons,
                                com.bmp.common.outbox.OutboxPublisher outbox) {
        this.closures = closures;
        this.bookingClient = bookingClient;
        this.salons = salons;
        this.outbox = outbox;
    }

    /**
     * The live bookings a closure window would strand, newest information first.
     *
     * <h2>Why this throws rather than returning an empty list</h2>
     * If bmp-booking is unreachable we do NOT know whether anybody is affected, and an empty list
     * is indistinguishable from "nobody is". That distinction is the entire safety property here:
     * a confident "0 affected" is what puts a customer in front of a locked shutter. The caller
     * turns this into -1 / "we couldn't check", which the UI renders as a warning rather than an
     * all-clear.
     */
    public List<com.bmp.salon.client.BookingServiceClient.AffectedBooking> affectedBookings(
            UUID salonId, Instant from, Instant to) {
        return bookingClient.bySalonWindow(salonId, from, to);
    }

    /**
     * Count only, or -1 when we could not find out. See {@link #affectedBookings}.
     *
     * <p>-1 rather than an exception because this is called while rendering a list of closures,
     * and one unreachable service must not blank the whole panel.
     */
    public int affectedCountOrUnknown(UUID salonId, Instant from, Instant to) {
        try {
            return affectedBookings(salonId, from, to).size();
        } catch (Exception e) {
            log.warn("Could not count bookings affected by the {}–{} closure at salon {} ({}). "
                    + "Reporting UNKNOWN rather than zero.", from, to, salonId, e.toString());
            return -1;
        }
    }

    /** Newest window first — what the owner sees on the closures panel. */
    public List<SalonClosure> list(UUID salonId) {
        return closures.findBySalonIdOrderByStartsAtDesc(salonId);
    }

    /**
     * Record a closure.
     *
     * <p>Validation is deliberately loud rather than corrective. Silently swapping a reversed
     * window, or clamping a wild date, produces a closure the owner did not ask for — and they
     * will not check, because the screen said it worked.
     */
    @Transactional
    public SalonClosure create(UUID salonId, Instant startsAt, Instant endsAt, String reason, UUID actor) {
        if (startsAt == null || endsAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CLOSURE_WINDOW_REQUIRED: both a start and an end time are needed.");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CLOSURE_WINDOW_INVALID: the closure must end after it starts.");
        }
        if (startsAt.isAfter(Instant.now().plus(MAX_DAYS_AHEAD, ChronoUnit.DAYS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CLOSURE_TOO_FAR_AHEAD: that date is more than a year away — check the year.");
        }
        /*
         * A closure entirely in the past cannot block a booking and cannot be acted on. Allowed
         * anyway, because "we were shut yesterday, that's why nobody came" is a legitimate record
         * — but logged, since the far more likely explanation is a mistyped date.
         */
        if (endsAt.isBefore(Instant.now())) {
            log.info("Salon {} recorded a closure entirely in the past ({} to {}) — allowed, but "
                    + "usually a typo.", salonId, startsAt, endsAt);
        }

        SalonClosure c = new SalonClosure(salonId, startsAt, endsAt,
                reason == null || reason.isBlank() ? null : reason.trim(), actor);
        closures.save(c);
        log.info("Salon {} closed from {} to {} (reason={})", salonId, startsAt, endsAt, c.getReason());

        notifyStrandedCustomers(salonId, c);
        return c;
    }

    /**
     * Warn everybody already booked inside the window. Session 48.
     *
     * <h2>Why this is a heads-up and not an outcome</h2>
     * Nothing has happened to these bookings yet — the owner still has to reschedule or refund
     * each one, and that may take until tomorrow. In the meantime the customer is holding an
     * appointment at a salon that will be shut, and without this they find out by turning up.
     *
     * <p>So the message is "the salon is closing then, they will be in touch", NOT "your booking
     * is cancelled". Most of these end up moved rather than cancelled, and telling somebody their
     * appointment is gone when it is about to be rescheduled is its own kind of harm. The real
     * outcome sends the ordinary cancellation or reschedule email when the owner acts.
     *
     * <h2>Never fatal</h2>
     * The closure is already recorded and already blocking new bookings — that is the part that
     * had to succeed. If bmp-booking is unreachable we log loudly and carry on; a closure that
     * failed to save because the notification failed would be strictly worse, and the owner can
     * still see the affected list on the panel.
     */
    private void notifyStrandedCustomers(UUID salonId, SalonClosure c) {
        String salonName = salons.findById(salonId).map(s -> s.getName()).orElse("your salon");
        try {
            var affected = bookingClient.bySalonWindow(salonId, c.getStartsAt(), c.getEndsAt());
            if (affected.isEmpty()) return;

            int told = 0;
            for (var b : affected) {
                if (b.customerEmail() == null || b.customerEmail().isBlank()) {
                    log.warn("Booking {} is inside salon {}'s closure but we hold no email for the "
                            + "customer — they have NOT been warned.", b.bookingRef(), salonId);
                    continue;
                }
                outbox.publish(new com.bmp.common.events.BookingAffectedByClosure(
                        b.id(), b.bookingRef(), salonName, b.serviceStart(),
                        c.getStartsAt(), c.getEndsAt(), c.getReason(),
                        b.customerEmail(), b.customerName()));
                told++;
            }
            log.info("Salon {} closure {}–{}: warned {} of {} affected customer(s).",
                    salonId, c.getStartsAt(), c.getEndsAt(), told, affected.size());
        } catch (Exception e) {
            log.error("Salon {} was closed but we could not reach bmp-booking to warn affected "
                    + "customers ({}). The closure IS in force; those customers have not been "
                    + "told and will need contacting from the Closures panel.", salonId, e.toString());
        }
    }

    /**
     * Reopen — cancel a closure that has not happened yet, or is happening now.
     *
     * <p>Soft: the row stays. Customers may already have been told about this closure, and we need
     * it to tell them it is off again. It is also the only record of why a booking was moved.
     */
    @Transactional
    public SalonClosure cancel(UUID salonId, UUID closureId) {
        SalonClosure c = closures.findById(closureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLOSURE_NOT_FOUND"));
        /*
         * The ownership check. Without it, any owner could cancel any other salon's closure by
         * guessing an id — the controller authorises the PATH (this owner, this salon) and would
         * otherwise trust the body's closureId completely. Same shape as the commission hole:
         * authorise the path, then trust the body.
         */
        if (!c.getSalonId().equals(salonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CLOSURE_NOT_FOUND");
        }
        c.cancel();
        closures.save(c);
        log.info("Salon {} reopened — closure {} cancelled", salonId, closureId);
        return c;
    }

    /** Is the salon shut at this instant? Used by anything that needs a yes/no rather than slots. */
    public boolean isClosedAt(UUID salonId, Instant when) {
        return closures.findActiveOverlapping(salonId, when, when.plusSeconds(1)).stream()
                .anyMatch(c -> c.covers(when));
    }
}
