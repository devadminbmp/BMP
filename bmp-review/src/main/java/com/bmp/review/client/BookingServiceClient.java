package com.bmp.review.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * bmp-review's only outbound call: "is this booking real, and is it theirs?" Session 54.
 *
 * <h2>Why bmp-review had no clients until now</h2>
 * {@code ReviewService.create} carried a TODO from the CRUD-first build order — <i>"call
 * bmp-booking-service to confirm booking.status == COMPLETED"</i> — and this service had no way to
 * make any outbound call at all. A deferral with no plumbing behind it is a deferral that does not
 * get picked up, and this one survived eleven sessions.
 *
 * <p>What it allowed: any account with {@code ROLE_CUSTOMER} could POST a review against any
 * booking id, including ids belonging to other people and ids that never existed. One review per
 * booking id was the entire defence. A competitor could one-star a salon; a salon could five-star
 * itself from a throwaway account.
 *
 * <p>Session 52 raised the stakes: stylist ratings now order the counter's availability picker, so
 * invented reviews change who gets offered work.
 *
 * <h2>Facts, not a verdict</h2>
 * This returns what bmp-booking knows. Whether those facts permit a review — is a cancelled
 * booking reviewable, how long afterwards — is review policy and is decided in
 * {@code ReviewService}, on this side of the boundary.
 *
 * <p>Mirrors {@code InternalBookingController.ReviewEligibility}; extra fields are ignored by
 * Jackson, but the names must match exactly.
 *
 * @param customerId null for a counter booking (V009) — there is no account that could have
 *                   written a review, and the caller refuses rather than treating null as a match
 */
@FeignClient(name = "bmp-booking-service",
             configuration = com.bmp.review.config.FeignInternalKeyConfig.class)
public interface BookingServiceClient {

    record ReviewEligibility(
            UUID bookingId, UUID salonId, UUID customerId, String status,
            Instant lastServiceEnd, List<UUID> stylistIds) {}

    /** 404 when the booking does not exist — which is the answer for a made-up id. */
    @GetMapping("/api/v1/bookings/internal/{bookingId}/review-eligibility")
    ReviewEligibility reviewEligibility(@PathVariable("bookingId") UUID bookingId);
}
