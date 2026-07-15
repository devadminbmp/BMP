package com.bmp.salon.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BMP-24 DTOs — salon_schema.stylist / stylist_salon / stylist_service. */
public final class StylistDtos {
    private StylistDtos() {}

    public record CreateStylistRequest(@NotBlank String name, UUID userId) {}

    public record StylistResponse(UUID id, String name, UUID userId, BigDecimal overallRating,
                                   int totalReviews, boolean isTopStylist, Instant createdAt) {}

    public record LinkStylistRequest(@NotNull UUID stylistId) {}

    public record StylistSalonResponse(UUID id, UUID stylistId, UUID salonId, String status,
                                        boolean isAvailableToday, BigDecimal salonRating,
                                        Integer salonReviewCount, Instant joinedAt, Instant leftAt) {}

    public record AvailableTodayRequest(boolean isAvailableToday) {}

    public record AvailableTodayResponse(UUID stylistId, UUID salonId, boolean isAvailableToday) {}

    public record StylistServiceRequest(@NotNull UUID serviceId, @NotNull int actualDurationMinutes, Long overridePricePaise) {}

    public record StylistServiceResponse(UUID id, UUID stylistId, UUID serviceId,
                                          int actualDurationMinutes, Long overridePricePaise) {}

    public record ErrorResponse(String error, String message) {}
}
