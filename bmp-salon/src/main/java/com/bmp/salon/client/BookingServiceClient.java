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

    @GetMapping("/api/v1/bookings/internal/busy-windows")
    BusyWindowsResponse getBusyWindows(@RequestParam("stylistId") UUID stylistId,
                                        @RequestParam("date") LocalDate date);
}
