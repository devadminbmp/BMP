package com.bmp.review.internal.repository;

import com.bmp.review.internal.entity.SalonResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SalonResponseRepository extends JpaRepository<SalonResponse, UUID> {
    Optional<SalonResponse> findByReviewId(UUID reviewId);
}
