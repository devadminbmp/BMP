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

    /**
     * One stylist and everything the picker needs to draw their card, including their free
     * times for the requested day. Session 52.
     *
     * <p>Name, speciality and rating travel WITH the slots on purpose. The old picker fetched
     * slots, then fetched the team, then joined them client-side — three states to reconcile and
     * a visible flash of "Stylist 4f2a…" before the names landed. One payload, one render.
     *
     * @param busy true when the stylist works today but has no room left. The card still shows,
     *             greyed, because "Anjali is full" is a useful answer and a missing row is not.
     */
    public record StylistDayAvailability(
        UUID stylistId,
        String name,
        String speciality,
        java.math.BigDecimal rating,
        int reviewCount,
        boolean busy,
        java.util.List<SlotResponse> slots
    ) {}

    /** The whole salon's day in one response. See AvailabilityService#salonDayAvailability. */
    public record SalonDayAvailability(
        java.time.LocalDate date,
        int durationMinutes,
        java.util.List<StylistDayAvailability> stylists
    ) {}

    public record ErrorResponse(String error, String message) {}
}
