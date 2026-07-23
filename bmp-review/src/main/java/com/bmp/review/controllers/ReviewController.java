package com.bmp.review.controllers;

import com.bmp.review.dto.ReviewDtos.*;
import com.bmp.review.services.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** BMP-27: review_schema.review + salon_response CRUD. */
@Tag(name = "Reviews", description = "review + salon_response CRUD. 7-day edit window on a review, 24h for a salon to respond. Editing review TEXT flags it for remoderation; a rating-only edit doesn't.")
@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @Operation(summary = "Leave a review for a completed booking")
    @PostMapping("/api/v1/bookings/{bookingId}/review")
    public ResponseEntity<ReviewResponse> create(@PathVariable UUID bookingId, @Valid @RequestBody CreateReviewRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(bookingId, req));
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
    public ReviewResponse update(@PathVariable UUID reviewId, @RequestBody UpdateReviewRequest req) {
        return service.update(reviewId, req);
    }

    @Operation(summary = "Salon responds to a review", description = "Only within 24h of the review being posted.")
    @PostMapping("/api/v1/reviews/{reviewId}/response")
    public ResponseEntity<SalonResponseDto> createResponse(@PathVariable UUID reviewId, @RequestBody ResponseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createResponse(reviewId, req));
    }

    @Operation(summary = "Edit a salon's response to a review")
    @PutMapping("/api/v1/reviews/{reviewId}/response")
    public SalonResponseDto updateResponse(@PathVariable UUID reviewId, @RequestBody ResponseRequest req) {
        return service.updateResponse(reviewId, req);
    }
}
