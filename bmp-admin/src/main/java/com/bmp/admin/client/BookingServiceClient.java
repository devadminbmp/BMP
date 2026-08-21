package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Booking lookups for support.
 *
 * <p>Search is by reference only — deliberate on bmp-booking's side, and worth repeating here:
 * free-text search over bookings is a browsing tool, and browsing customer records is the thing
 * an internal console shouldn't make easy.
 *
 * <p>Cancel and the event trail use the CUSTOMER-facing endpoints, because those already let
 * {@code ROLE_SERVICE} through. Duplicating them as internal variants would create two paths to
 * keep consistent, and one would drift.
 */
@FeignClient(name = "bmp-booking-service", configuration = com.bmp.admin.config.FeignInternalKeyConfig.class)
public interface BookingServiceClient {

    record SupportBookingItem(String name, Instant start, UUID stylistId, long pricePaise) {}

    record SupportBooking(
        UUID id, String bookingRef, String status, UUID salonId, UUID customerId,
        Instant scheduledStart, long finalAmountPaise, long totalRefundedPaise,
        Instant createdAt, List<SupportBookingItem> items
    ) {}

    record BookingEvent(String eventType, String actorType, UUID actorId, Instant createdAt) {}

    record CancelRequest(String reason) {}

    @GetMapping("/api/v1/bookings/internal/search")
    List<SupportBooking> search(@RequestParam("q") String query);

    @GetMapping("/api/v1/bookings/internal/by-customer/{customerId}")
    List<SupportBooking> byCustomer(@PathVariable("customerId") UUID customerId);

    /** Platform-wide, by the salon's local calendar day — for the ops overview. */
    @GetMapping("/api/v1/bookings/internal/count-today")
    long countToday();

    @GetMapping("/api/v1/bookings/{bookingId}/events")
    List<BookingEvent> events(@PathVariable("bookingId") UUID bookingId);

    /**
     * Cancel on the customer's behalf.
     *
     * <p>The state machine models cancellation as a CUSTOMER action — there is no staff-actor
     * cancel — so bmp-admin acts as the customer here. That's exactly why the console requires
     * a reason and audits it: the entry is the only thing distinguishing "support cancelled at
     * the customer's request" from "support cancelled somebody's appointment".
     */
    @PostMapping("/api/v1/bookings/{bookingId}/cancel")
    Object cancel(@PathVariable("bookingId") UUID bookingId, @RequestBody CancelRequest request);
}
