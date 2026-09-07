package com.bmp.salon.client;

import com.bmp.salon.client.dto.BusyWindowsResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Session 8 (availability algorithm): the "what is this stylist already committed to"
 * half of the free-slots computation — the other half (working hours, breaks, leave,
 * walk-in blocks) is entirely local to bmp-salon. See AvailabilityService.
 *
 * <h2>EVERY {@code LocalDate} PARAM HERE NEEDS {@code @DateTimeFormat}. Session 66 — a real bug.</h2>
 * Without it, Feign encodes a {@code LocalDate} query parameter through Spring's conversion
 * service, which falls back to a formatter derived from the <b>JVM's default locale</b>. On an
 * Indian or British locale that produces {@code 06/09/2026}; on a US one, {@code 9/6/2026}.
 *
 * <p>The receiving controller declares {@code @DateTimeFormat(iso = ISO.DATE)} and so parses only
 * {@code 2026-09-06}. It therefore rejects everything this client sends, with:
 *
 * <pre>
 *   [500] Failed to convert value of type 'java.lang.String' to required type
 *         'java.time.LocalDate' ... for value [06/09/2026]
 * </pre>
 *
 * <p>What made this expensive to find is that it is <b>locale-dependent</b>: it works on a
 * developer machine whose default locale happens to format dates as ISO, and fails on everyone
 * else's — so it looks like an environment problem rather than a code one. It took down walk-ins,
 * the "who's free" picker and counter bookings simultaneously, because all three ultimately call
 * busy-windows.
 *
 * <p>The annotation is not decoration. It selects the ISO formatter explicitly instead of
 * inheriting whatever the host machine's locale implies, which is the only way this contract is
 * stable across machines. {@code SecurityConstantsTest}'s sibling in bmp-salon —
 * {@code FeignDateEncodingTest} — asserts it on every date param so a new endpoint cannot
 * reintroduce this.
 */
@FeignClient(name = "bmp-booking-service", configuration = com.bmp.salon.config.FeignInternalKeyConfig.class)
public interface BookingServiceClient {

    /**
     * @param excludeBookingId Session 37. Null in the ordinary case. Set when a booking is being
     *                         RESCHEDULED, so it doesn't collide with its own current slot —
     *                         without it, moving a 60-minute service from 11:00 to 11:30 is
     *                         refused because 11:00–12:00 is "busy" with the booking being
     *                         moved. Everyone else's bookings stay visible.
     */
    /**
     * Every stylist's busy windows for one salon-day, in ONE call. Session 52.
     *
     * <p>The per-stylist call below is still used by the single-stylist path. This one exists
     * because {@code freeSlotsAnyStylist} used to make that call once per stylist, serially —
     * six stylists meant six cross-service round trips for one "what's free today?".
     */
    @GetMapping("/api/v1/bookings/internal/busy-windows/salon")
    SalonBusyWindows salonBusyWindows(@RequestParam("salonId") java.util.UUID salonId,
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                       @RequestParam("date") java.time.LocalDate date,
                                       @RequestParam("stylistIds") java.util.List<java.util.UUID> stylistIds,
                                       @RequestParam(value = "excludeBookingId", required = false)
                                       java.util.UUID excludeBookingId);

    /** A stylist with nothing booked is ABSENT from the map — use getOrDefault. */
    record SalonBusyWindows(
            java.util.Map<java.util.UUID, java.util.List<BusyWindowsResponse.Window>> byStylist) {}

    @GetMapping("/api/v1/bookings/internal/busy-windows")
    BusyWindowsResponse getBusyWindows(@RequestParam("stylistId") UUID stylistId,
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                        @RequestParam("date") LocalDate date,
                                        @RequestParam(value = "excludeBookingId", required = false) UUID excludeBookingId);

    /**
     * One live booking caught inside a closure window. Session 48.
     *
     * <p>Mirrors bmp-booking's AffectedBookingResponse field-for-field. Feign maps by NAME, so a
     * rename on either side silently produces nulls rather than a compile error — the pair is
     * covered by the contract guard in CI for exactly that reason.
     */
    record AffectedBooking(
        java.util.UUID id,
        String bookingRef,
        String status,
        java.time.Instant serviceStart,
        String customerName,
        String customerEmail,
        long finalAmountPaise) {}

    /**
     * Who is affected if this salon shuts between {@code from} and {@code to}?
     *
     * <p>Excludes cancelled, completed and no-show on the far side — a closure cannot affect a
     * booking that already happened or was already called off.
     */
    @GetMapping("/api/v1/bookings/internal/by-salon-window")
    java.util.List<AffectedBooking> bySalonWindow(
            @RequestParam("salonId") java.util.UUID salonId,
            @RequestParam("from") java.time.Instant from,
            @RequestParam("to") java.time.Instant to);
}
