package com.bmp.review.services;

import com.bmp.review.dto.ReviewDtos.*;
import com.bmp.review.entities.Review;
import com.bmp.review.entities.SalonResponse;
import com.bmp.review.repositories.ReviewRepository;
import com.bmp.review.repositories.SalonResponseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** BMP-27: review + salon_response CRUD. */
@Service
public class ReviewService {

    private static final Duration EDIT_WINDOW = Duration.ofDays(7);
    private static final Duration RESPONSE_WINDOW = Duration.ofHours(24);

    private final ReviewRepository reviews;
    private final SalonResponseRepository responses;

    public ReviewService(ReviewRepository reviews, SalonResponseRepository responses) {
        this.reviews = reviews;
        this.responses = responses;
    }

    @Transactional
    public ReviewResponse create(UUID bookingId, CreateReviewRequest req) {
        // TODO(Phase 3 / inter-service): call bmp-booking-service (Feign) to confirm
        // booking.status == COMPLETED before allowing a review. Skipped in this
        // CRUD-first pass per the team's phased build order (CRUD now, inter-service later).
        if (reviews.existsByBookingId(bookingId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_ALREADY_EXISTS");
        }
        Instant now = Instant.now();
        Review r = new Review(bookingId, req.salonId(), req.stylistId(), req.salonRating(),
                req.stylistRating() == null ? 0 : req.stylistRating(), req.text(),
                now.plus(EDIT_WINDOW), false, null);
        r = reviews.save(r);
        return toResponse(r);
    }

    public ReviewResponse getById(UUID id) {
        return reviews.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
    }

    public PagedReviews listForSalon(UUID salonId, int page, int size) {
        Page<Review> p = reviews.findBySalonId(salonId, PageRequest.of(page, size));
        return new PagedReviews(p.getContent().stream().map(this::toResponse).toList(),
                page, size, p.getTotalElements());
    }

    @Transactional
    public ReviewResponse update(UUID id, UpdateReviewRequest req) {
        Review r = reviews.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
        if (Instant.now().isAfter(r.getEditLockedAt())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "REVIEW_EDIT_WINDOW_CLOSED");
        }
        boolean textChanged = req.text() != null && !Objects.equals(req.text(), r.getReviewText());
        if (req.salonRating() != null) r.setSalonRating(req.salonRating());
        if (req.stylistRating() != null) r.setStylistRating(req.stylistRating());
        if (req.text() != null) r.setReviewText(req.text());
        if (textChanged) r.setNeedsRemoderation(true); // rating-only edits do NOT set this flag
        r.touch();
        return toResponse(r);
    }

    @Transactional
    public SalonResponseDto createResponse(UUID reviewId, ResponseRequest req) {
        reviews.findById(reviewId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
        SalonResponse resp = responses.findByReviewId(reviewId).orElse(null);
        if (resp != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESPONSE_ALREADY_EXISTS");
        }
        Review r = reviews.findById(reviewId).orElseThrow();
        resp = new SalonResponse(reviewId, r.getSalonId(), req.text());
        resp = responses.save(resp);
        return toResponseDto(resp);
    }

    @Transactional
    public SalonResponseDto updateResponse(UUID reviewId, ResponseRequest req) {
        SalonResponse resp = responses.findByReviewId(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RESPONSE_NOT_FOUND"));
        if (Instant.now().isAfter(resp.getCreatedAt().plus(RESPONSE_WINDOW))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESPONSE_EDIT_WINDOW_CLOSED");
        }
        resp.setResponseText(req.text());
        resp.touch();
        return toResponseDto(resp);
    }

    private ReviewResponse toResponse(Review r) {
        return new ReviewResponse(r.getId(), r.getBookingId(), r.getSalonId(), r.getStylistId(),
                r.getSalonRating(), r.getStylistRating(), r.getReviewText(), r.getEditLockedAt(),
                r.isNeedsRemoderation(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private SalonResponseDto toResponseDto(SalonResponse r) {
        return new SalonResponseDto(r.getId(), r.getReviewId(), r.getSalonId(), r.getResponseText(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
