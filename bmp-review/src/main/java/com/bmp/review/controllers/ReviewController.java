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
     * <p><b>Session 54 — attendance IS now verified.</b> This javadoc previously read "still does
     * NOT verify the customer attended… so this remains open to a logged-in customer reviewing a
     * booking that isn't theirs", which was accurate and stayed true for eleven sessions.
     * {@code ReviewService.requireReviewable} now confirms, against bmp-booking, that the booking
     * exists, belongs to the caller, is COMPLETED, and finished inside the review window.
     *
     * <p>The salon and stylist on the review are taken from the BOOKING. The request still carries
     * both for backward compatibility and both are ignored — trusting them let a genuine customer
     * attach a genuine review to a salon they never visited.
     */
    @Operation(summary = "Leave a review for a completed booking",
               description = "The author is taken from your token, never the body. Session 54: the booking must exist, be yours, be COMPLETED, and have finished within the last 90 days — 404 / 403 / 409 respectively. The salon and stylist are resolved from the booking; the ones in the body are ignored.")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/api/v1/bookings/{bookingId}/review")
    public ResponseEntity<ReviewResponse> create(@PathVariable UUID bookingId,
                                                  @Valid @RequestBody CreateReviewRequest req,
                                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(bookingId, req, caller.userId()));
    }

    /**
     * The review this customer already left for this booking, or 404. Session 54.
     *
     * <h2>Why it is scoped to the author, not public</h2>
     * A salon's reviews are public and readable at {@code /salons/{id}/reviews}. This route
     * answers a different question — "have I reviewed this yet?" — and the booking id is the key.
     * Making it public would turn a booking id into a lookup for whether that specific
     * appointment was reviewed and how, which is the customer's business.
     *
     * <p>{@code requireAuthor} is what makes it theirs: the review's {@code authorUserId} must be
     * the caller. Without it, any customer holding a booking id could read its review.
     */
    @Operation(summary = "The review I left for this booking",
               description = "Your own review for this booking, or 404 if you haven't left one. Used so the app shows your rating instead of offering a button that would be refused as a duplicate.")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/api/v1/bookings/{bookingId}/review")
    public ReviewResponse myReviewForBooking(@PathVariable UUID bookingId,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.myReviewForBooking(bookingId, caller.userId());
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

    /**
     * What customers said about one stylist. Session 48.
     *
     * <h2>Why the path is {@code /api/v1/reviews/...} and not {@code /api/v1/stylists/...}</h2>
     * The gateway routes {@code /api/v1/stylists/**} to bmp-salon-service. A stylist-shaped path
     * here would never reach this service — it would 404 at the gateway, and the symptom would
     * look like a broken screen rather than a routing mistake. Same class of bug as Session 44's
     * missing {@code /api/v1/coupon-requests/**} route.
     *
     * <p>(Related, and NOT fixed here: {@code GET /api/v1/salons/&#123;id&#125;/reviews} above has
     * the same problem — {@code /api/v1/salons/**} routes to bmp-salon, so that endpoint is
     * unreachable through the gateway today. Flagged rather than fixed, because it needs a route
     * ordered ahead of salon-service and that is a change worth making deliberately.)
     *
     * <h2>Why it needs a login but not a specific role</h2>
     * Two segments after {@code /reviews}, so this service's {@code /api/v1/reviews/*} public-path
     * does not match it — Ant's single {@code *} is one segment. A token is therefore required.
     *
     * <p>Any signed-in user may read it, and that is intentional: review content is public by
     * nature (salon reviews already are), and restricting it to the stylist themselves would mean
     * bmp-review needed to know the user→stylist mapping, which lives in bmp-salon. The response
     * carries no author identity, so there is nothing here a customer could not already see on the
     * salon's page.
     */
    @Operation(summary = "Reviews about one stylist",
               description = "Rating, 1-5 distribution and the reviews themselves. Carries NO "
                   + "customer identity — a stylist cannot tell who left a bad review.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/v1/reviews/stylist/{stylistId}")
    public StylistReviews listForStylist(@PathVariable UUID stylistId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.listForStylist(stylistId, page, Math.min(size, 100));
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
