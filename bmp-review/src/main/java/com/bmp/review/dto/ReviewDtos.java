package com.bmp.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-27 DTOs — review_schema.review + salon_response. */
public final class ReviewDtos {
    private ReviewDtos() {}

    public record CreateReviewRequest(
        @NotNull UUID salonId, UUID stylistId,
        @Min(1) @Max(5) int salonRating,
        @Min(1) @Max(5) Integer stylistRating,
        String text
    ) {}

    public record UpdateReviewRequest(Integer salonRating, Integer stylistRating, String text) {}

    public record ReviewResponse(
        UUID id, UUID bookingId, UUID salonId, UUID stylistId, int salonRating,
        Integer stylistRating, String text, Instant editLockedAt, boolean needsRemoderation,
        Instant createdAt, Instant updatedAt
    ) {}

    public record PagedReviews(List<ReviewResponse> content, int page, int size, long totalElements) {}

    public record ResponseRequest(String text) {}

    public record SalonResponseDto(UUID id, UUID reviewId, UUID salonId, String text,
                                    Instant createdAt, Instant updatedAt) {}

    public record ErrorResponse(String error, String message) {}
}
