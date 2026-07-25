package com.bmp.booking.client;

import com.bmp.booking.client.dto.AvailabilitySlot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Session 10 — the booking-creation side of wiring in the availability algorithm.
 * BookingService calls this to (a) confirm a requested slot for a specific stylist is
 * still free at the moment of booking (protects against the customer having viewed slots
 * a while ago and someone else grabbing it first), and (b) resolve an actual stylist for
 * an "any_available" item that didn't come with one attached.
 *
 * <p>Note the resulting bidirectional coupling: bmp-booking calls bmp-salon here, and
 * bmp-salon's own AvailabilityService calls back into bmp-booking (its busy-windows
 * endpoint) to answer THIS call. This is intentional and not circular in the harmful
 * sense — different endpoints, no recursion — but worth knowing when reasoning about
 * this pair of services' runtime dependencies. See docs/AVAILABILITY_ALGORITHM.md.
 */
@FeignClient(name = "bmp-salon-service", configuration = com.bmp.booking.config.FeignInternalKeyConfig.class)
public interface SalonAvailabilityClient {

    @GetMapping("/api/v1/availability/slots")
    List<AvailabilitySlot> freeSlots(@RequestParam("salonId") UUID salonId,
                                       @RequestParam("stylistId") UUID stylistId,
                                       @RequestParam("date") LocalDate date,
                                       @RequestParam("durationMinutes") int durationMinutes);

    @GetMapping("/api/v1/availability/slots/any")
    List<AvailabilitySlot> freeSlotsAnyStylist(@RequestParam("salonId") UUID salonId,
                                                 @RequestParam("date") LocalDate date,
                                                 @RequestParam("durationMinutes") int durationMinutes);
}
