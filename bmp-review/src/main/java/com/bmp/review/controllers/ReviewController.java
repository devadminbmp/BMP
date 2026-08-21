package com.bmp.review.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.review.dto.ReviewDtos.*;
import com.bmp.review.services.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Reviews and salon replies.
 *
 * <h2>SESSION 40 — EVERY WRITE HERE WAS UNPROTECTED</h2>
 * Not one method had {@code @PreAuthorize}, and this service's {@code public-paths} contains
 * {@code /api/v1/reviews/*}, written so a review can be READ without logging in. <b>public-paths
 * are path-only and method-blind</b> — the pattern doesn't say "GET", it says "this URL" — so
 * {@code PUT /api/v1/reviews/{id}} was reachable with no credential whatsoever. Anyone on the
 * internet could rewrite any review on the platform within its edit window.
 *
 * <p>The salon-reply endpoints needed a login but nothing more, so any customer could post a
 * public reply <b>attributed to any salon</b>. That is worse than editing a review: the salon
 * can't see it happening and the customer has no reason to doubt it.
 *
 * <p>Session 29's lesson was "a path is not a permission", found on an open wallet-credit
 * endpoint. This is the same lesson inverted — a path that legitimately should be open for one
 * verb was open for all of them. Reads stay public; writes are gated here.
 */
@Tag(name = "Reviews", description = "review + salon_response CRUD. 7-day edit window on a review, 24h for a salon to respond. Editing review TEXT flags it for remoderation; a rating-only edit doesn't.")
@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    /**
     * CUSTOMER only, and the author is taken from the token.
     *
     * <p>Still does NOT verify the customer attended — that check needs bmp-booking and is
     * PENDING_WORK S3. So this remains open to a logged-in customer reviewing a booking that
     * isn't theirs. Narrower than before (it was open to anyone with any role) and not yet right;
     * recording the author is what makes the eventual check possible.
     */
    @Operation(summary = "Leave a review for a completed booking",
               description = "The author is taken from your token, never the body. Attendance is "
                   + "NOT yet verified — see PENDING_WORK S3.")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/api/v1/bookings/{bookingId}/review")
    public ResponseEntity<ReviewResponse> create(@PathVariable UUID bookingId,
                                                  @Valid @RequestBody CreateReviewRequest req,
                                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(bookingId, req, caller.userId()));
    }

    @Operation(summary = "Get a review by id")
    @GetMapping("/api/v1/reviews/{reviewId}")
    public ReviewResponse getById(@PathVariable UUID reviewId) {
        return service.getById(reviewId);
    }

    @Operation(summary = "List a salon's reviews", description = "Paginated.")
    @GetMapping("/api/v1/salons/{salonId}/reviews")
    public PagedReviews listForSalon(@PathVariable UUID salonId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.listForSalon(salonId, page, size);
    }

    @Operation(summary = "Edit a review", description = "Only within 7 days of creation. Editing the review text (not just the star rating) sets needsRemoderation.")
    @PutMapping("/api/v1/reviews/{reviewId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ReviewResponse update(@PathVariable UUID reviewId, @RequestBody UpdateReviewRequest req,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.update(reviewId, req, caller.userId());
    }

    @Operation(summary = "Salon responds to a review", description = "Only within 24h of the review being posted.")
    @PostMapping("/api/v1/reviews/{reviewId}/response")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public ResponseEntity<SalonResponseDto> createResponse(@PathVariable UUID reviewId,
                                                            @RequestBody ResponseRequest req,
                                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createResponse(reviewId, req, caller.salonId()));
    }

    @Operation(summary = "Edit a salon's response to a review")
    @PutMapping("/api/v1/reviews/{reviewId}/response")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public SalonResponseDto updateResponse(@PathVariable UUID reviewId, @RequestBody ResponseRequest req,
                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.updateResponse(reviewId, req, caller.salonId());
    }
}
