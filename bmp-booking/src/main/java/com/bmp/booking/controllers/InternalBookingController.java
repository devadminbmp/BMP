package com.bmp.booking.controllers;

import com.bmp.booking.api.BookingStatus;
import com.bmp.booking.entities.Booking;
import com.bmp.booking.entities.BookingServiceItem;
import com.bmp.booking.repositories.BookingRepository;
import com.bmp.booking.repositories.BookingServiceItemRepository;
import com.bmp.common.time.BmpTimeZone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Internal booking lookups for the staff console — {@code ROLE_SERVICE} only, called by
 * bmp-admin.
 *
 * <h2>Why a separate controller from the customer-facing one</h2>
 * {@link BookingController}'s endpoints are scoped to "your own bookings" or "your own salon's".
 * A support agent needs neither: they need to find a booking someone is complaining about,
 * across every salon, from a reference read aloud over the phone.
 *
 * <p>Keeping that on its own service-only controller means the customer-facing authorization
 * stays simple and strict — no "unless the caller happens to be staff" branch threaded through
 * it, which is exactly the sort of condition that later gets loosened by accident.
 *
 * <p>Note what ISN'T here: cancel, and the event trail. Both already work for bmp-admin through
 * {@link BookingController}, because its checks let {@code ROLE_SERVICE} through. Adding
 * duplicates would mean two code paths to keep consistent, and one of them would eventually
 * drift.
 */
@Tag(name = "Internal bookings (staff console)", description = "Service-to-service lookups for support: find a booking by reference across all salons, and count a day's bookings.")
@RestController
@RequestMapping("/api/v1/bookings/internal")
@PreAuthorize("hasRole('SERVICE')")
public class InternalBookingController {

    private final BookingRepository bookings;
    private final BookingServiceItemRepository items;

    public InternalBookingController(BookingRepository bookings, BookingServiceItemRepository items) {
        this.bookings = bookings;
        this.items = items;
    }

    public record SupportBookingItem(String name, Instant start, UUID stylistId, long pricePaise) {}

    /**
     * One booking caught inside a salon closure. Session 48.
     *
     * <p>Carries the customer's NAME and EMAIL, unlike SupportBookingResponse which carries ids.
     * That is deliberate and it is the whole point of the endpoint: the owner has to contact these
     * people, and making them look each one up separately is how three of the four get forgotten.
     * The snapshot columns (V006) already hold this on the booking row, so no extra lookup.
     */
    public record AffectedBookingResponse(
        UUID id, String bookingRef, String status, Instant serviceStart,
        String customerName, String customerEmail, long finalAmountPaise
    ) {}

    /**
     * Everything bmp-review needs to decide whether a review is legitimate. Session 54.
     *
     * <h2>Why this endpoint exists</h2>
     * {@code ReviewService.create} carried a TODO reading <i>"call bmp-booking-service to confirm
     * booking.status == COMPLETED before allowing a review — skipped in this CRUD-first pass"</i>.
     * The result was that ANY logged-in customer could post a review against ANY booking id,
     * including ids that were never theirs and ids that never existed. The only guard was one
     * review per booking id.
     *
     * <p>That was survivable while reviews were decoration. Session 52 made stylist ratings order
     * the counter's availability picker, so fake reviews now change who gets offered work.
     *
     * <h2>It returns facts, not a verdict</h2>
     * bmp-booking says who the booking belongs to, what state it is in, which salon it was at and
     * who worked on it. bmp-review decides what that means for a review, because the rules
     * (is a CANCELLED booking reviewable? how long afterwards?) are review policy and belong on
     * that side of the boundary.
     *
     * @param customerId  null for a counter booking (V009) — nobody can review one, because there
     *                    is no account to have written it. Handled by the caller, not hidden here.
     * @param stylistIds  every stylist actually assigned. A review naming somebody who never
     *                    touched this booking is the other half of the same abuse.
     */
    /**
     * How many bookings this customer has made, ever. Session 56.
     *
     * <p>A COUNT, not a list. bmp-admin already has {@code by-customer/{id}} which returns up to
     * fifty full bookings — using that to render "11th booking" on a queue row would pull fifty
     * objects per row to produce one integer, which is why the console carried a TODO saying it
     * was "not worth a second call per row yet". It is, at one integer.
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "How many bookings a customer has made")
    @GetMapping("/count-by-customer/{customerId}")
    public java.util.Map<String, Long> countByCustomer(@PathVariable UUID customerId) {
        return java.util.Map.of("total", bookings.countByCustomerId(customerId));
    }

    public record ReviewEligibility(
        UUID bookingId, UUID salonId, UUID customerId, String status,
        Instant lastServiceEnd, List<UUID> stylistIds
    ) {}

    @io.swagger.v3.oas.annotations.Operation(
        summary = "Facts a review can be checked against",
        description = "SERVICE only. Who owns this booking, its state, its salon and its stylists — so bmp-review can refuse a review nobody earned. 404 if the booking doesn't exist, which is itself the answer to a made-up id.")
    @GetMapping("/{bookingId}/review-eligibility")
    public ReviewEligibility reviewEligibility(@PathVariable UUID bookingId) {
        Booking b = bookings.findById(bookingId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        List<com.bmp.booking.entities.BookingServiceItem> its = items.findByBookingId(bookingId);
        return new ReviewEligibility(
                b.getId(), b.getSalonId(), b.getCustomerId(), b.getStatus().name(),
                its.stream().map(com.bmp.booking.entities.BookingServiceItem::getServiceEnd)
                        .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null),
                its.stream().map(com.bmp.booking.entities.BookingServiceItem::getAssignedStylistId)
                        .filter(java.util.Objects::nonNull).distinct().toList());
    }

    public record SupportBookingResponse(
        UUID id, String bookingRef, String status, UUID salonId, UUID customerId,
        Instant scheduledStart, long finalAmountPaise, long totalRefundedPaise,
        Instant createdAt, List<SupportBookingItem> items
    ) {}

    /**
     * Find bookings by reference.
     *
     * <p>Reference only, deliberately. A free-text search across every booking is a browsing
     * tool, and browsing customer bookings is what you can't defend afterwards — the same
     * reasoning as the console having no customer list. An agent gets the reference from the
     * customer, or finds the customer first and works from their bookings.
     *
     * <p>Matching is case-insensitive and partial from the start, because people read out
     * "seven seven four one" and drop the prefix.
     */
    @Operation(summary = "Find a booking by reference", description = "Case-insensitive prefix/contains match on booking_ref. Reference only — not a free-text search over customer bookings.")
    @GetMapping("/search")
    public List<SupportBookingResponse> search(@RequestParam("q") String query) {
        String q = query == null ? "" : query.trim().toUpperCase();
        if (q.length() < 3) {
            // Two characters would match half the table and turn this into the browsing tool
            // it's designed not to be.
            return List.of();
        }
        return bookings.findByBookingRefContainingIgnoreCase(q).stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(25)
                .map(this::toResponse)
                .toList();
    }

    /**
     * Live bookings for one salon inside an arbitrary window. Session 48.
     *
     * <h2>Why this exists</h2>
     * A salon closes for Diwali. Four customers already have appointments that afternoon. Somebody
     * has to be told, and until now nothing could even answer the question "who". The closure
     * feature is worthless without this — blocking NEW bookings while silently keeping the
     * existing ones is how a customer arrives at a locked shutter.
     *
     * <h2>Only live bookings</h2>
     * CANCELLED, COMPLETED and NO_SHOW are excluded. A closure does not affect a booking that has
     * already happened or already been called off, and listing them would bury the handful that
     * genuinely need action in a list of things that do not — which is the same as not listing
     * them at all.
     *
     * <p>Note this DIFFERS from findSalonDayItems' own rule, which deliberately keeps cancelled
     * rows so a manager can see a slot freed up. Same query, different question, different filter.
     */
    @Operation(summary = "Live bookings for a salon inside a time window",
               description = "Used by bmp-salon when a closure is created, to tell the owner who is affected. Excludes cancelled, completed and no-show — a closure cannot affect those.")
    @GetMapping("/by-salon-window")
    public List<AffectedBookingResponse> bySalonWindow(
            @RequestParam UUID salonId,
            @RequestParam Instant from,
            @RequestParam Instant to) {

        if (!to.isAfter(from)) {
            // A backwards window silently matches nothing, and "nothing is affected" is exactly
            // the wrong answer to be confidently wrong about.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "WINDOW_INVALID: 'to' must be after 'from'.");
        }

        var itemsInWindow = items.findSalonDayItems(salonId, from, to, null);
        if (itemsInWindow.isEmpty()) return List.of();

        // Items are per-service; one booking can have several inside the window. Group by booking
        // and keep the EARLIEST start — that is when the customer actually turns up, and it is
        // what the owner needs to see when deciding what to do.
        java.util.Map<UUID, Instant> earliestByBooking = new java.util.LinkedHashMap<>();
        for (var i : itemsInWindow) {
            earliestByBooking.merge(i.getBookingId(), i.getServiceStart(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }

        return bookings.findAllById(earliestByBooking.keySet()).stream()
                .filter(b -> switch (b.getStatus()) {
                    case CANCELLED, COMPLETED, NO_SHOW -> false;
                    default -> true;
                })
                .map(b -> new AffectedBookingResponse(
                        b.getId(), b.getBookingRef(), b.getStatus().name(),
                        earliestByBooking.get(b.getId()),
                        b.getCustomerName(), b.getCustomerEmail(),
                        b.getFinalAmountPaise() == null ? 0L : b.getFinalAmountPaise().paise()))
                .sorted(java.util.Comparator.comparing(AffectedBookingResponse::serviceStart))
                .toList();
    }

    /** Every booking a customer has made — the "what have they got with us" view. */
    @Operation(summary = "A customer's bookings", description = "Used when support has found the person and needs their history.")
    @GetMapping("/by-customer/{customerId}")
    public List<SupportBookingResponse> byCustomer(@PathVariable UUID customerId) {
        return bookings.findByCustomerId(customerId, org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * How many bookings start today, platform-wide.
     *
     * <p>For the console's ops overview. "Today" is the salon's local day (Asia/Kolkata), not
     * the server's — the same rule the manager desk follows, so the two never disagree in front
     * of someone comparing them.
     */
    @Operation(summary = "Count today's bookings", description = "Platform-wide, by the salon's local calendar day.")
    @GetMapping("/count-today")
    public long countToday() {
        LocalDate today = LocalDate.now(BmpTimeZone.ZONE);
        Instant from = today.atStartOfDay(BmpTimeZone.ZONE).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(BmpTimeZone.ZONE).toInstant();
        return items.countDistinctBookingsStartingBetween(from, to);
    }

    private SupportBookingResponse toResponse(Booking b) {
        List<BookingServiceItem> bookingItems = items.findByBookingId(b.getId());
        Instant earliest = bookingItems.stream()
                .map(BookingServiceItem::getServiceStart)
                .min(Instant::compareTo)
                .orElse(null);

        return new SupportBookingResponse(
                b.getId(), b.getBookingRef(), b.getStatus().name(), b.getSalonId(), b.getCustomerId(),
                earliest, b.getFinalAmountPaise().paise(), b.getTotalRefundedPaise().paise(),
                b.getCreatedAt(),
                bookingItems.stream()
                        .map(i -> new SupportBookingItem(
                                i.getNameSnapshot(), i.getServiceStart(),
                                i.getAssignedStylistId(), i.getPricePaiseSnapshot().paise()))
                        .toList());
    }
}
