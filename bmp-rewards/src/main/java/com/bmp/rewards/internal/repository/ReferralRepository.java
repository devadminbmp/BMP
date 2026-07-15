package com.bmp.rewards.internal.repository;

import com.bmp.rewards.internal.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {
}
