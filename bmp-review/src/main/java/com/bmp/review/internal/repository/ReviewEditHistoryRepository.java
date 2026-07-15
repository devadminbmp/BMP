package com.bmp.review.internal.repository;

import com.bmp.review.internal.entity.ReviewEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReviewEditHistoryRepository extends JpaRepository<ReviewEditHistory, UUID> {
}
