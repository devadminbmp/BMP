package com.bmp.review.internal.repository;

import com.bmp.review.internal.entity.SalonRatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonRatingSnapshotRepository extends JpaRepository<SalonRatingSnapshot, UUID> {
}
