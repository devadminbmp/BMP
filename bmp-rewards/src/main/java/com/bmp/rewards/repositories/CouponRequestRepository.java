package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRequestRepository extends JpaRepository<CouponRequest, UUID> {

    Optional<CouponRequest> findByRequestRef(String requestRef);

    /**
     * The admin queue: oldest pending FIRST.
     *
     * <p>Not newest-first, deliberately. A goodwill request is attached to a customer who is
     * already unhappy and already waiting; working newest-first means the oldest — the person
     * who has waited longest — is served last. Newest-first is the natural default in most
     * lists and the wrong one in every queue.
     */
    List<CouponRequest> findByStatusOrderByCreatedAtAsc(String status);

    /** Everything, newest first — the history view, where recency IS what you want. */
    List<CouponRequest> findAllByOrderByCreatedAtDesc();

    /** "What have I asked for?" — a requester's own list, whoever they are. */
    List<CouponRequest> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId);

    /** A salon owner's requests, and anything raised on their salon's behalf. */
    List<CouponRequest> findBySalonIdOrderByCreatedAtDesc(UUID salonId);

    /** Feeds the auto-expiry sweep — pending requests older than the cut-off. */
    List<CouponRequest> findByStatusAndCreatedAtBefore(String status, Instant before);

    long countByStatus(String status);
}
