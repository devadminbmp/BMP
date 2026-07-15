package com.bmp.salon.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-23 DTOs — salon_schema.salon / salon_policy / salon_hours / salon_service. */
public final class SalonDtos {
    private SalonDtos() {}

    public record LatLng(@NotNull double lat, @NotNull double lng) {}

    public record CreateSalonRequest(@NotBlank String name, @NotNull LatLng location, String stylistAssignmentStrategy) {}

    public record UpdateSalonRequest(String name, LatLng location, String status, String stylistAssignmentStrategy) {}

    public record SalonResponse(UUID id, String name, LatLng location, String status,
                                 String stylistAssignmentStrategy, Instant createdAt, Instant updatedAt) {}

    public record NearbySalonResponse(UUID id, String name, double distanceKm) {}

    public record PolicyRequest(@NotBlank String template, int freeCancelHours, int lateGraceMinutes,
                                 boolean requirePrepayment, int slotGranularityMinutes) {}

    public record PolicyResponse(UUID id, UUID salonId, String template, int freeCancelHours,
                                  int lateGraceMinutes, boolean requirePrepayment, int slotGranularityMinutes) {}

    public record HourEntry(int dayOfWeek, @NotBlank String openTime, @NotBlank String closeTime) {}

    public record HoursRequest(@NotEmpty List<@Valid HourEntry> hours) {}

    public record HoursResponse(UUID salonId, List<HourEntry> hours) {}

    public record ServiceRequest(@NotBlank String name, @NotNull long pricePaise,
                                  @NotNull int durationMinutes, boolean requiresStylistAssignment) {}

    public record ServiceResponse(UUID id, UUID salonId, String name, long pricePaise,
                                   int durationMinutes, boolean requiresStylistAssignment) {}

    public record ErrorResponse(String error, String message) {}
}
