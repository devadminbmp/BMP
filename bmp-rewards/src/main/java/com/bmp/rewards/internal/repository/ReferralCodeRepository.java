package com.bmp.rewards.internal.repository;

import com.bmp.rewards.internal.entity.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {
    Optional<ReferralCode> findByUserId(UUID userId);
}
