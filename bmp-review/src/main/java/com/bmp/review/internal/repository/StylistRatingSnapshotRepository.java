package com.bmp.review.internal.repository;

import com.bmp.review.internal.entity.StylistRatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StylistRatingSnapshotRepository extends JpaRepository<StylistRatingSnapshot, UUID> {
}
