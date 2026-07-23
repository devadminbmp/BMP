package com.bmp.salon.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.UUID;

/** Session 8 (availability algorithm) — request/response shapes for AvailabilityController.
 * Mirrors com.bmp.salon.api.AvailabilityApi's Slot record as a proper response DTO. */
public final class AvailabilityDtos {
    private AvailabilityDtos() {}

    public record SlotResponse(LocalTime start, LocalTime end, UUID stylistId) {}

    public record BlockWalkInRequest(
        @NotNull UUID salonId,
        @NotNull UUID stylistId,
        @NotNull java.time.LocalDate date,
        @NotNull LocalTime start,
        @Min(1) int durationMinutes
    ) {}

    public record ErrorResponse(String error, String message) {}
}
