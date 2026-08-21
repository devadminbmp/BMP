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
    Optional<SalonReview> findBySalonId(UUID salonId);

    long countByStatus(String status);
}
