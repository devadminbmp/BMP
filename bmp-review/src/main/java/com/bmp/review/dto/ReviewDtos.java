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

    /**
     * @param salonId   <b>IGNORED since Session 54.</b> Kept so existing clients keep compiling and
     *                  sending; the salon is resolved from the booking. Trusting this field let a
     *                  real customer with a real appointment attach the review to a different
     *                  salon entirely — the same class of mistake as a client-supplied price.
     * @param stylistId honoured ONLY if that stylist actually worked on this booking. Otherwise it
     *                  and {@code stylistRating} are dropped and the salon rating is kept, because
     *                  losing a genuine review to a stale id on the client is the worse outcome.
     */
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

    /**
     * What customers said about one stylist. Session 48.
     *
     * <p>Deliberately carries no customer identity — not a name, not an id. A stylist reading
     * "3 stars, rushed" does not get to find out who said it. Salon reviews already work this way;
     * the difference here is that the criticism is of a named individual, which makes the
     * temptation to look, and the harm if they could, both larger.
     *
     * @param averageRating null when nobody has rated them yet — <b>not</b> 0.0, which renders as
     *                      one star and would libel a stylist on their first day
     * @param ratedReviews  how many reviews actually carry a rating for them; the count under the
     *                      average, so a 5.0 from one customer isn't read as a settled reputation
     * @param distribution  index 0 = one star … index 4 = five stars
     */
    public record StylistReviews(
        UUID stylistId,
        Double averageRating,
        long ratedReviews,
        List<Long> distribution,
        List<ReviewResponse> content,
        int page, int size, long totalElements
    ) {}

    public record ResponseRequest(String text) {}

    public record SalonResponseDto(UUID id, UUID reviewId, UUID salonId, String text,
                                    Instant createdAt, Instant updatedAt) {}

    public record ErrorResponse(String error, String message) {}
}
