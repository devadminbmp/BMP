package com.bmp.review.repositories;

import com.bmp.review.entities.ReviewPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReviewPromptRepository extends JpaRepository<ReviewPrompt, UUID> {
}
