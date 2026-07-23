package com.bmp.review.repositories;

import com.bmp.review.entities.StylistRatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StylistRatingSnapshotRepository extends JpaRepository<StylistRatingSnapshot, UUID> {
}
