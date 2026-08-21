package com.bmp.salon.client;

import com.bmp.salon.client.dto.BusyWindowsResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Session 8 (availability algorithm): the "what is this stylist already committed to"
 * half of the free-slots computation — the other half (working hours, breaks, leave,
 * walk-in blocks) is entirely local to bmp-salon. See AvailabilityService.
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
    @GetMapping("/api/v1/bookings/internal/busy-windows")
    BusyWindowsResponse getBusyWindows(@RequestParam("stylistId") UUID stylistId,
                                        @RequestParam("date") LocalDate date,
                                        @RequestParam(value = "excludeBookingId", required = false) UUID excludeBookingId);
}
