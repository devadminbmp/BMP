package com.bmp.review.services;

import com.bmp.review.dto.ReviewDtos.*;
import com.bmp.review.entities.Review;
import com.bmp.review.entities.SalonResponse;
import com.bmp.review.repositories.ReviewRepository;
import com.bmp.review.repositories.SalonResponseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** BMP-27: review + salon_response CRUD. */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final Duration EDIT_WINDOW = Duration.ofDays(7);
    private static final Duration RESPONSE_WINDOW = Duration.ofHours(24);

    /**
     * How long after the appointment a review may be left. Session 54.
     *
     * <p>Ninety days. Long enough that somebody who forgot can still write one, short enough that
     * a dormant account cannot be woken up two years later to bury a salon under old bookings.
     * There is no product requirement behind the exact number — it is a Darshan-only default, same
     * ratification flag as every other one in this repo — but "no window at all" is not a neutral
     * choice, it is an open door.
     */
    private static final Duration REVIEW_WINDOW = Duration.ofDays(90);

    private final ReviewRepository reviews;
    private final SalonResponseRepository responses;
    /** Session 54 — the check that makes a review mean something. See {@link #create}. */
    private final com.bmp.review.client.BookingServiceClient bookings;

    public ReviewService(ReviewRepository reviews, SalonResponseRepository responses,
                          com.bmp.review.client.BookingServiceClient bookings) {
        this.reviews = reviews;
        this.responses = responses;
        this.bookings = bookings;
    }

    /**
     * Confirm this person actually had this appointment, and that it happened. Session 54.
     *
     * <h2>What was wrong</h2>
     * This method used to be a comment:
     *
     * <pre>  // TODO(Phase 3 / inter-service): call bmp-booking-service to confirm
     *  // booking.status == COMPLETED before allowing a review. Skipped in this CRUD-first pass.</pre>
     *
     * <p>With {@code @PreAuthorize("hasRole('CUSTOMER')")} as the only gate, ANY logged-in customer
     * could review ANY booking id — one that belonged to somebody else, or one that never existed.
     * The sole defence was one review per booking id, which stops a second fake review, not a
     * first. A competitor could one-star a salon from a throwaway account; a salon could five-star
     * itself.
     *
     * <p>It also let the CLIENT supply {@code salonId} and {@code stylistId}. So even a genuine
     * customer reviewing their own real appointment could attach that review to a different salon,
     * or credit a stylist who was never there. Same class as the Session 30 price bug: <b>fields
     * the client cannot be trusted with are not read from the client.</b> Both are now taken from
     * the booking and the request's copies are ignored.
     *
     * <h2>Five refusals, each with a message the customer can act on</h2>
     * <ol>
     *   <li>The booking does not exist → 404.</li>
     *   <li>It is not theirs → 403. Deliberately NOT 404: they are authenticated and this is a
     *       real booking, so pretending otherwise only confuses an honest customer who mistyped.</li>
     *   <li>It is a counter booking ({@code customerId} null, V009) → 403. Nobody can review one:
     *       there is no account that could have written it.</li>
     *   <li>It has not been completed → 409. Reviewing an appointment you have not attended is
     *       the most common honest mistake and the easiest abuse; the message says to come back
     *       afterwards.</li>
     *   <li>It finished more than {@link #REVIEW_WINDOW} ago → 409.</li>
     * </ol>
     *
     * <h2>bmp-booking unreachable REFUSES the review</h2>
     * A 503, not a shrug. This is the deliberate opposite of the contact-snapshot rule in
     * bmp-booking, and the distinction is the same one: a missing confirmation email costs a
     * customer nothing they cannot recover, whereas a review admitted without its check is
     * permanent, public, and moves money — it changes a salon's rating and, since Session 52, the
     * order stylists are offered at the counter. Failing open here would restore the exact hole
     * this method exists to close, and would do it silently every time bmp-booking restarted.
     */
    private com.bmp.review.client.BookingServiceClient.ReviewEligibility requireReviewable(
            UUID bookingId, UUID authorUserId) {

        com.bmp.review.client.BookingServiceClient.ReviewEligibility b;
        try {
            b = bookings.reviewEligibility(bookingId);
        } catch (feign.FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "BOOKING_NOT_FOUND: we can't find that appointment.");
        } catch (Exception e) {
            log.error("Could not verify booking {} for a review by {} ({}). REFUSING the review — "
                    + "an unverified review is permanent and public.", bookingId, authorUserId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't check your appointment just now. Please try again in a moment.");
        }

        if (b.customerId() == null) {
            // A counter booking (V009). The person exists in the salon's own book, not as a BMP
            // account, so no logged-in user can be its author.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "NOT_YOUR_BOOKING: that appointment wasn't booked through your account.");
        }
        if (!b.customerId().equals(authorUserId)) {
            log.warn("User {} tried to review booking {}, which belongs to {}.",
                    authorUserId, bookingId, b.customerId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "NOT_YOUR_BOOKING: you can only review your own appointments.");
        }
        if (!"COMPLETED".equals(b.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "BOOKING_NOT_COMPLETED: you can leave a review once the appointment is done.");
        }
        if (b.lastServiceEnd() != null
                && b.lastServiceEnd().isBefore(Instant.now().minus(REVIEW_WINDOW))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "REVIEW_WINDOW_CLOSED: this appointment is more than "
                    + REVIEW_WINDOW.toDays() + " days old.");
        }
        return b;
    }

    @Transactional
    public ReviewResponse create(UUID bookingId, CreateReviewRequest req, UUID authorUserId) {
        /*
         * Session 54 — the check this method spent eleven sessions without. See
         * requireReviewable for what was possible before it, and why an unreachable
         * bmp-booking refuses the review rather than waving it through.
         *
         * Done BEFORE the duplicate check on purpose: "you can only review your own
         * appointments" is the more useful answer than "a review already exists", and running
         * the cheap local check first would leak whether a booking id has been reviewed to
         * somebody who has no business knowing the id exists.
         */
        var booking = requireReviewable(bookingId, authorUserId);

        if (reviews.existsByBookingId(bookingId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_ALREADY_EXISTS");
        }
        Instant now = Instant.now();
        /*
         * Session 48: this line used to read `req.stylistRating() == null ? 0 : ...`, because the
         * entity field was a primitive. It stored a rating of ZERO for every customer who rated the
         * salon and skipped the stylist — outside the column's documented 1-5 range, and invisible
         * until stylists could see their own reviews, at which point it became a 0-star review
         * nobody left. Null is passed straight through now; V005 cleaned up the zeros and added a
         * CHECK so this cannot be reintroduced quietly.
         */
        /*
         * SALON AND STYLIST COME FROM THE BOOKING, NOT THE REQUEST.
         *
         * `CreateReviewRequest` still carries salonId and stylistId — the shape is unchanged so
         * older clients keep working — but both are IGNORED here. Trusting them let a genuine
         * customer, reviewing a genuine appointment, attach the review to a salon they never
         * visited or credit a stylist who was never in the room. Same rule as the price in
         * Session 30: fields with consequences are resolved server-side.
         *
         * The stylist is only accepted if they actually worked on THIS booking. A rating for
         * anybody else is dropped rather than rejected: the salon rating is the substance of the
         * review, and refusing the whole thing over a stale stylist id would lose a real review
         * to a client-side bug.
         */
        UUID stylistId = null;
        if (req.stylistId() != null && booking.stylistIds() != null
                && booking.stylistIds().contains(req.stylistId())) {
            stylistId = req.stylistId();
        } else if (req.stylistId() != null) {
            log.warn("Review for booking {} named stylist {}, who did not work on it — the stylist "
                    + "rating has been dropped and the salon rating kept.", bookingId, req.stylistId());
        }
        // A stylist rating with no valid stylist would be a score attached to nobody.
        Integer stylistRating = stylistId == null ? null : req.stylistRating();

        Review r = new Review(bookingId, booking.salonId(), stylistId, req.salonRating(),
                stylistRating, req.text(),
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

    /**
     * This customer's own review for one booking. Session 54.
     *
     * <p>404 when they have not left one — the ordinary case, and what the app treats as "offer
     * the button". 404 ALSO when a review exists but was written by somebody else: revealing
     * "a review exists here but it isn't yours" tells a caller holding a guessed booking id more
     * than they should learn, and there is no honest flow in which that happens.
     */
    public ReviewResponse myReviewForBooking(UUID bookingId, UUID callerUserId) {
        return reviews.findByBookingId(bookingId)
                .filter(r -> callerUserId != null && callerUserId.equals(r.getAuthorUserId()))
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_REVIEW"));
    }

    public ReviewResponse getById(UUID id) {
        return reviews.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
    }

    public PagedReviews listForSalon(UUID salonId, int page, int size) {
        Page<Review> p = reviews.findBySalonIdAndHiddenAtIsNull(salonId, PageRequest.of(page, size));
        return new PagedReviews(p.getContent().stream().map(this::toResponse).toList(),
                page, size, p.getTotalElements());
    }

    /**
     * Everything customers said about one stylist. Session 48.
     *
     * <h2>Why this is computed and not read from the snapshot</h2>
     * {@code stylist_rating_snapshot} exists for exactly this number and is the right thing on a
     * hot path. It is written by an aggregation job that does not run in development and lags in
     * production — so a stylist opening their profile the morning after their first review would
     * see "no rating yet". That reads as the app having lost it, and it is the single moment they
     * are most likely to care.
     *
     * <p>The volume here is a person's own reviews, not a salon's feed. Reading the rows directly
     * is cheap and always correct; switch to the snapshot when a stylist has thousands.
     *
     * <h2>What it deliberately does not return</h2>
     * No author id, no customer name. {@link ReviewResponse} has never carried
     * {@code authorUserId} and must not start — a stylist looking at a two-star review should not
     * be able to work out which of yesterday's customers left it.
     */
    public StylistReviews listForStylist(UUID stylistId, int page, int size) {
        Page<Review> p = reviews.findByStylistIdAndStylistRatingIsNotNullAndHiddenAtIsNull(
                stylistId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        // Null when there are no rated reviews — see the DTO. Rounded to one decimal because two
        // implies a precision that four reviews do not have.
        Double avg = reviews.averageStylistRating(stylistId);
        Double rounded = (avg == null) ? null : Math.round(avg * 10.0) / 10.0;

        // index 0 = one star … index 4 = five stars.
        List<Long> dist = new ArrayList<>(List.of(0L, 0L, 0L, 0L, 0L));
        for (Object[] row : reviews.stylistRatingHistogram(stylistId)) {
            int stars = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            // Defensive: V005 adds a CHECK for 1-5, but this code also runs against databases
            // migrated before it. An out-of-range value is skipped rather than thrown, because a
            // bad histogram bucket is not worth failing the whole profile screen over.
            if (stars >= 1 && stars <= 5) dist.set(stars - 1, count);
            else log.warn("Review row for stylist {} has an out-of-range stylist_rating of {} — "
                    + "pre-V005 data. Excluded from the histogram.", stylistId, stars);
        }

        long rated = dist.stream().mapToLong(Long::longValue).sum();
        return new StylistReviews(stylistId, rounded, rated, dist,
                p.getContent().stream().map(this::toResponse).toList(),
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
