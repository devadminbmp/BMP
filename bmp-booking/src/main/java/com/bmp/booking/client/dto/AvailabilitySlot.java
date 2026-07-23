package com.bmp.booking.client.dto;

import java.time.LocalTime;
import java.util.UUID;

/** Mirrors bmp-salon's AvailabilityDtos.SlotResponse — a plain client-side copy, same
 * convention as every other inter-service Feign DTO in this repo (see bmp-auth's client/
 * package, bmp-salon's own BusyWindowsResponse). */
public record AvailabilitySlot(LocalTime start, LocalTime end, UUID stylistId) {}
