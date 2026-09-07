package com.bmp.booking.controllers;

import com.bmp.booking.dto.BookingDtos.BusyWindowsResponse;
import com.bmp.booking.services.BookingAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Session 8 (availability algorithm) — internal, service-to-service only. Called by
 * bmp-salon's AvailabilityApi implementation over Feign to find out what a stylist is
 * already committed to on a given date, so those windows can be excluded from free slots.
 * Locked to ROLE_SERVICE the same way bmp-salon's StaffController locks its /internal/**
 * endpoints — not reachable with a normal user token even though it's on the public gateway
 * route (bmp-booking hasn't had its own authorization pass yet, see CommonSecurityConfig's
 * public-paths note, but this specific endpoint is deliberately restricted regardless).
 */
@Tag(name = "Booking Availability (internal)", description = "Busy-window lookup for the availability algorithm. Service-to-service only (ROLE_SERVICE).")
@RestController
@RequestMapping("/api/v1/bookings/internal")
public class BookingAvailabilityController {

    private final BookingAvailabilityService service;

    public BookingAvailabilityController(BookingAvailabilityService service) {
        this.service = service;
    }

    @Operation(summary = "[internal] Busy windows for a stylist on a date", description = "Active booking_service_items + unexpired slot_locks, as LocalTime windows in IST (see com.bmp.common.time.BmpTimeZone). Called by bmp-salon-service's AvailabilityApi implementation.")
    @GetMapping("/busy-windows")
    @PreAuthorize("hasRole('SERVICE')")
    public BusyWindowsResponse busyWindows(@RequestParam UUID stylistId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                            // Session 37. Optional, and only ever set during a
                                            // reschedule — a booking must not block itself. See
                                            // BookingServiceItemRepository.findBusyItemsForStylist.
                                            @RequestParam(required = false) UUID excludeBookingId) {
        return new BusyWindowsResponse(service.getBusyWindows(stylistId, date, excludeBookingId));
    }

    /**
     * Every stylist's busy windows for one salon-day, in ONE call. Session 52.
     *
     * <p>Replaces N calls to {@code /busy-windows} — one per stylist — which is what made the
     * availability picker slow. bmp-salon sends its team's ids because slot_lock has no salon
     * column; the item side is filtered by salon directly.
     */
    @Operation(summary = "[internal] Busy windows for a whole salon on a date",
               description = "One call instead of one per stylist. A stylist with nothing booked "
                   + "is absent from the map, not present with an empty list.")
    @GetMapping("/busy-windows/salon")
    @PreAuthorize("hasRole('SERVICE')")
    public SalonBusyWindowsResponse salonBusyWindows(
            @RequestParam UUID salonId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) java.util.List<UUID> stylistIds,
            @RequestParam(required = false) UUID excludeBookingId) {
        return new SalonBusyWindowsResponse(service.getBusyWindowsForSalon(
                salonId, date, stylistIds == null ? java.util.List.of() : stylistIds,
                excludeBookingId));
    }

    /** Keyed by stylist id. Jackson serialises the UUID keys as strings, which Feign reverses. */
    public record SalonBusyWindowsResponse(
            java.util.Map<UUID, java.util.List<com.bmp.booking.dto.BookingDtos.BusyWindow>> byStylist) {}
}
