package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.Referral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    /**
     * Session 22: was this customer referred by someone?
     *
     * <p>Drives the {@code referred_users} coupon audience. A person can only be referred once
     * — the schema's {@code fraud_reason} includes {@code already_referred} for exactly that —
     * so a single result is the expected shape.
     */
    Optional<Referral> findByRefereeUserId(UUID refereeUserId);

    /** Someone's referrals, for their "you've invited 3 people" view. */
    List<Referral> findByReferrerUserId(UUID referrerUserId);

    boolean existsByRefereeUserId(UUID refereeUserId);
}
