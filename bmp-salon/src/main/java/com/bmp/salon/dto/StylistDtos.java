package com.bmp.salon.dto;

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

    /**
     * Session 16: {@code stylistName} added. The link row only ever carried ids, which meant
     * every consumer (the manager desk, the booking screen) had to fan out one call per
     * stylist just to render a list — or show raw UUIDs to a human. The name lives one table
     * away in the same service, so joining it here is strictly cheaper than making every
     * caller do it. Nullable only if the stylist row is missing, which shouldn't happen.
     *
     * <p>Session 26: {@code stylistUserId} added, and it is NOT redundant with {@code stylistId}.
     * A stylist is a salon-owned record that can exist with no login at all — six of the seven
     * seeded stylists have {@code user_id = NULL}, because a salon lists its chair staff long
     * before any of them installs the app. So {@code stylist.id} and {@code users.id} are
     * different identifiers for different things, and only some stylists have both.
     *
     * <p>Without this field a signed-in stylist could not be matched to their own row, which is
     * what a stylist dashboard needs before it can show "my schedule". The frontend previously
     * had no way to do it and there was no stylist dashboard at all — those two facts are
     * related. Nullable: a stylist who has never signed up simply has no user.
     *
     * <p>Costs nothing: {@code toStylistSalonResponse} already loads the Stylist row for the
     * name, so this reads a field off an object that is already in hand.
     */
    public record StylistSalonResponse(UUID id, UUID stylistId, String stylistName, UUID stylistUserId,
                                        UUID salonId, String status, boolean isAvailableToday,
                                        BigDecimal salonRating, Integer salonReviewCount,
                                        Instant joinedAt, Instant leftAt) {}

    public record AvailableTodayRequest(boolean isAvailableToday) {}

    public record AvailableTodayResponse(UUID stylistId, UUID salonId, boolean isAvailableToday) {}

    public record StylistServiceRequest(@NotNull UUID serviceId, @NotNull int actualDurationMinutes, Long overridePricePaise) {}

    public record StylistServiceResponse(UUID id, UUID stylistId, UUID serviceId,
                                          int actualDurationMinutes, Long overridePricePaise) {}

    public record ErrorResponse(String error, String message) {}
}
