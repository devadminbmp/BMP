package com.bmp.review.repositories;

import com.bmp.review.entities.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>V006, Session 60 — every customer-facing read here filters {@code hidden_at IS NULL}.</b>
 *
 * <p>A moderated review must disappear from the salon's page, the stylist's page, the average and
 * the histogram. Missing any one of those is how "we removed it" turns out to mean "we removed it
 * from one of four places" — and the star average is the one people notice last and trust most.
 *
 * <p>{@code findByBookingId} deliberately does NOT filter: it backs the uniqueness check and the
 * author's own "did I review this?" lookup, and a hidden review still occupies its booking. Hiding
 * it there would let the customer write a second one.
 */
public interface ReviewRepository extends JpaRepository<Review, UUID> {
    Optional<Review> findByBookingId(UUID bookingId);

    /**
     * Everything one person wrote. Session 60, for the DPDP data export.
     *
     * <p>Includes HIDDEN reviews, unlike every other read here. A data-subject access request asks
     * what we hold about them, and a review we took down is still something we hold — arguably the
     * most important thing to disclose, since it is the one they do not know about.
     */
    List<Review> findByAuthorUserIdOrderByCreatedAtDesc(UUID authorUserId);
    boolean existsByBookingId(UUID bookingId);
    /**
     * A salon's reviews, EXCLUDING moderated ones. V006, Session 60.
     *
     * <p>Was {@code findBySalonId}. Renaming rather than adding a second method is deliberate: a
     * method still called {@code findBySalonId} sitting beside a filtered one is an invitation for
     * the next reader to pick the wrong one, and the wrong one puts abusive content back on a
     * public page. There is no unfiltered variant, because nothing customer-facing wants one.
     */
    Page<Review> findBySalonIdAndHiddenAtIsNull(UUID salonId, Pageable pageable);

    /**
     * Reviews naming this stylist. Session 48.
     *
     * <h2>Why {@code stylistRatingIsNotNull} and not just {@code findByStylistId}</h2>
     * {@code stylist_id} is set whenever a booking had a stylist assigned, but
     * {@code stylist_rating} is only set when the customer actually rated <em>them</em> — the
     * schema comment says "mandatory only if stylist assigned", and rows predating that, or where
     * the customer rated the salon and skipped the stylist, carry a null.
     *
     * <p>Including those would show a stylist a page of reviews about the salon's parking, filed
     * under their name, with no rating attached. The page is "what customers said about ME", so
     * the filter is the rating, not the assignment.
     */
    Page<Review> findByStylistIdAndStylistRatingIsNotNullAndHiddenAtIsNull(UUID stylistId, Pageable pageable);

    /**
     * The stylist's own average, computed from the rows rather than read from a snapshot.
     *
     * <p>{@code stylist_rating_snapshot} exists and is the right thing for hot read paths, but it
     * is written by an aggregation job — so on a fresh database, or minutes after a new review, it
     * is empty or stale. A stylist looking at their own profile and seeing "no rating" the day
     * after their first five-star review would reasonably conclude the app lost it.
     *
     * <p>Returns null (not zero) when there are no rated reviews. Zero would render as one star.
     */
    @Query("""
           SELECT AVG(r.stylistRating)
           FROM Review r
           WHERE r.stylistId = :stylistId AND r.stylistRating IS NOT NULL
             AND r.hiddenAt IS NULL
           """)
    Double averageStylistRating(@Param("stylistId") UUID stylistId);

    /** How many of this stylist's reviews carry each star value, for the 1–5 breakdown. */
    @Query("""
           SELECT r.stylistRating, COUNT(r)
           FROM Review r
           WHERE r.stylistId = :stylistId AND r.stylistRating IS NOT NULL
             AND r.hiddenAt IS NULL
           GROUP BY r.stylistRating
           """)
    List<Object[]> stylistRatingHistogram(@Param("stylistId") UUID stylistId);
}
