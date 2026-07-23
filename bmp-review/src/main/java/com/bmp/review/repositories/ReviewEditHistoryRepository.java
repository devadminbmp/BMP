package com.bmp.review.repositories;

import com.bmp.review.entities.ReviewEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReviewEditHistoryRepository extends JpaRepository<ReviewEditHistory, UUID> {
}
