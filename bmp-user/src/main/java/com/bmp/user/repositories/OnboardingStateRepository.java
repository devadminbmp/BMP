package com.bmp.user.repositories;

import com.bmp.user.entities.OnboardingState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OnboardingStateRepository extends JpaRepository<OnboardingState, UUID> {
    Optional<OnboardingState> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
