package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    Optional<ReferralCode> findByUserId(UUID userId);

    /**
     * Session 22: look up who a shared code belongs to.
     *
     * <p>The whole point of a referral code — someone types it at signup and we need to find
     * the person who gave it to them. Also used for the collision check when generating.
     */
    Optional<ReferralCode> findByCode(String code);
}
