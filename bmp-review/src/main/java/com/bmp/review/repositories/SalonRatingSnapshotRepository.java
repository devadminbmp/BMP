package com.bmp.review.repositories;

import com.bmp.review.entities.SalonRatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonRatingSnapshotRepository extends JpaRepository<SalonRatingSnapshot, UUID> {
}
