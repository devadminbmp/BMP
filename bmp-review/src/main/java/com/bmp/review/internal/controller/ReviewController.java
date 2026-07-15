package com.bmp.review.internal.controller;

import com.bmp.review.internal.dto.ReviewDtos.*;
import com.bmp.review.internal.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** BMP-27: review_schema.review + salon_response CRUD. */
@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/bookings/{bookingId}/review")
    public ResponseEntity<ReviewResponse> create(@PathVariable UUID bookingId, @Valid @RequestBody CreateReviewRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(bookingId, req));
    }

    @GetMapping("/api/v1/reviews/{reviewId}")
    public ReviewResponse getById(@PathVariable UUID reviewId) {
        return service.getById(reviewId);
    }

    @GetMapping("/api/v1/salons/{salonId}/reviews")
    public PagedReviews listForSalon(@PathVariable UUID salonId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.listForSalon(salonId, page, size);
    }

    @PutMapping("/api/v1/reviews/{reviewId}")
    public ReviewResponse update(@PathVariable UUID reviewId, @RequestBody UpdateReviewRequest req) {
        return service.update(reviewId, req);
    }

    @PostMapping("/api/v1/reviews/{reviewId}/response")
    public ResponseEntity<SalonResponseDto> createResponse(@PathVariable UUID reviewId, @RequestBody ResponseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createResponse(reviewId, req));
    }

    @PutMapping("/api/v1/reviews/{reviewId}/response")
    public SalonResponseDto updateResponse(@PathVariable UUID reviewId, @RequestBody ResponseRequest req) {
        return service.updateResponse(reviewId, req);
    }
}
