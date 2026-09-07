package com.bmp.admin.repositories;

import com.bmp.admin.entities.SalonReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalonReviewRepository extends JpaRepository<SalonReview, UUID> {

    /** Oldest first: a queue is worked in the order salons joined it. */
    List<SalonReview> findByStatusOrderBySubmittedAtAsc(String status);

    List<SalonReview> findAllByOrderBySubmittedAtDesc();

    /** One review row per salon — see the unique index in V003. */
    /**
     * The salon's LATEST review. V007 (Session 46) — a salon may now have several, one per
     * submission, so "the review for this salon" had to become "the most recent one".
     *
     * <p>The old {@code findBySalonId} was safe only because a unique index guaranteed at most
     * one row; that index is gone precisely so a rejection survives a retry. Every caller has to
     * say which one it means now, and that is the point.
     */
    Optional<SalonReview> findFirstBySalonIdOrderBySubmittedAtDesc(UUID salonId);

    /**
     * An OPEN review for this salon, if any.
     *
     * <p>This is what {@code enqueue}'s idempotency actually meant. Its javadoc warns that
     * "whatever triggers it will eventually fire twice", and the unique index used to absorb
     * that — while also forbidding the legitimate second submission after a rejection. Asking
     * "is one already pending?" allows the retry and forbids the duplicate, which is the rule
     * that was intended.
     */
    Optional<SalonReview> findFirstBySalonIdAndStatusOrderBySubmittedAtDesc(UUID salonId, String status);

    /** Every submission for a salon, newest first — the history a second reviewer needs. */
    List<SalonReview> findBySalonIdOrderBySubmittedAtDesc(UUID salonId);

    long countByStatus(String status);
}
