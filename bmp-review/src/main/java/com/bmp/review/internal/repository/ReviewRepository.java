package com.bmp.review.internal.repository;

import com.bmp.review.internal.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {
    Optional<Review> findByBookingId(UUID bookingId);
    boolean existsByBookingId(UUID bookingId);
    Page<Review> findBySalonId(UUID salonId, Pageable pageable);
}
