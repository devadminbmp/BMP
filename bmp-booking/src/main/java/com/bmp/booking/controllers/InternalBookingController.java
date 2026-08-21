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
