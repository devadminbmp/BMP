package com.bmp.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-25 DTOs — booking_schema.booking + booking_service_item + booking_events. */
public final class BookingDtos {
    private BookingDtos() {}

    /**
     * Item fields (serviceId/name/pricePaise/durationMinutes) are supplied directly by the
     * client in this ticket's scope. Production should look these up live from
     * bmp-salon-service (Feign) instead of trusting the client — TODO(Phase 3 / inter-service).
     */
    public record ItemRequest(
        @NotNull UUID serviceId, UUID stylistId, @NotBlank String selectionType,
        @NotNull Instant start, @NotNull String nameSnapshot, @NotNull long pricePaise,
        @NotNull int durationMinutes
    ) {}

    public record CreateBookingRequest(
        @NotNull UUID salonId, @NotNull UUID customerId,
        @NotEmpty List<@Valid ItemRequest> items
    ) {}

    public record ItemResponse(
        UUID id, UUID serviceId, UUID assignedStylistId, String selectionType,
        Instant serviceStart, Instant serviceEnd, String nameSnapshot, long pricePaiseSnapshot,
        int durationShownMinutes, String itemStatus
    ) {}

    public record BookingResponse(
        UUID id, String bookingRef, UUID salonId, UUID customerId, String status,
        long finalAmountPaise, long totalRefundedPaise, Instant createdAt,
        List<ItemResponse> items
    ) {}

    public record PagedBookings(List<BookingResponse> content, int page, int size, long totalElements) {}

    public record CancelRequest(String reason) {}

    public record EventResponse(String eventType, String actorType, UUID actorId, Instant createdAt) {}

    public record ErrorResponse(String error, String message) {}
}
