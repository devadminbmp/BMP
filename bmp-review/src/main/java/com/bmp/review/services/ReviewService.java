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
    public ReviewResponse create(UUID bookingId, CreateReviewRequest req, UUID authorUserId) {
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
        // V004 (Session 40) — a review now knows who wrote it. Without this there was nothing to
        // check on edit, so "only customers may edit" still meant "any customer may edit anyone's".
        r.setAuthorUserId(authorUserId);
        r = reviews.save(r);
        return toResponse(r);
    }

    /**
     * The salon in the JWT must be the salon on the review.
     *
     * <p>The role says what KIND of person you are; the {@code salonId} claim says WHICH salon.
     * Only the second one keeps an owner out of another shop's reviews — the same pairing every
     * salon-scoped endpoint in bmp-salon and bmp-booking uses.
     */
    private void requireOwnSalon(UUID reviewSalonId, UUID callerSalonId) {
        if (callerSalonId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "NO_SALON_SCOPE — your account isn't attached to a salon.");
        }
        if (!callerSalonId.equals(reviewSalonId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "REVIEW_BELONGS_TO_ANOTHER_SALON");
        }
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

    /**
     * Edit a review, within its window, <b>by its author</b>.
     *
     * <h2>Session 40 — this was reachable with no credential at all</h2>
     * No {@code @PreAuthorize}, and the path is matched by this service's public-paths entry
     * {@code /api/v1/reviews/*} — which was written for the GET. public-paths are path-only and
     * <b>method-blind</b>: the pattern doesn't say "GET", it says "this URL". So anyone on the
     * internet could rewrite any review on the platform.
     *
     * <p>Session 29 recorded "a path is not a permission" after finding an open wallet-credit
     * endpoint. This is the same lesson inverted: a path that should be open for one verb was
     * open for all of them.
     *
     * @param callerUserId from the JWT, never the body
     */
    @Transactional
    public ReviewResponse update(UUID id, UpdateReviewRequest req, UUID callerUserId) {
        Review r = reviews.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        /*
         * Pre-V004 rows have no author. Refused rather than allowed: an unattributable review is
         * one nobody can prove they own, and defaulting to "allow" here would leave the original
         * hole open for exactly the rows most likely to be someone else's.
         */
        if (r.getAuthorUserId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "REVIEW_HAS_NO_AUTHOR: this review predates authorship tracking and can't be "
                    + "edited. Contact support.");
        }
        if (!r.getAuthorUserId().equals(callerUserId)) {
            // Same 403 wording as a closed window on purpose — a different message here would let
            // someone probe which review ids exist and who they belong to.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_YOUR_REVIEW");
        }
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

    /**
     * The salon's public reply. Session 40 added the check that it is <b>this</b> salon's.
     *
     * <p>Previously any authenticated user could post a reply attributed to any salon — a reply
     * that renders publicly under the salon's name. Impersonating a business in its own reviews
     * is a worse outcome than editing a review, because the salon can't see it happening and the
     * customer has no reason to doubt it.
     *
     * @param callerSalonId from the JWT's salonId claim, never the body
     */
    @Transactional
    public SalonResponseDto createResponse(UUID reviewId, ResponseRequest req, UUID callerSalonId) {
        Review r = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
        requireOwnSalon(r.getSalonId(), callerSalonId);

        SalonResponse resp = responses.findByReviewId(reviewId).orElse(null);
        if (resp != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESPONSE_ALREADY_EXISTS");
        }
        resp = new SalonResponse(reviewId, r.getSalonId(), req.text());
        resp = responses.save(resp);
        return toResponseDto(resp);
    }

    @Transactional
    public SalonResponseDto updateResponse(UUID reviewId, ResponseRequest req, UUID callerSalonId) {
        SalonResponse resp = responses.findByReviewId(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RESPONSE_NOT_FOUND"));
        requireOwnSalon(resp.getSalonId(), callerSalonId);
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
