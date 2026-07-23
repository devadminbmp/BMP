package com.bmp.salon.client.dto;

import java.time.LocalTime;
import java.util.List;

/** Mirrors bmp-booking's BookingDtos.BusyWindowsResponse — kept as a plain client-side
 * copy rather than a shared bmp-common DTO, same convention as the rest of this repo's
 * inter-service Feign clients (see bmp-auth's client/ package). */
public record BusyWindowsResponse(List<Window> windows) {
    public record Window(LocalTime start, LocalTime end, String source) {}
}
