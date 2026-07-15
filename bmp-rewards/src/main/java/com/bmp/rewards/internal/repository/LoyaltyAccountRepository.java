package com.bmp.rewards.internal.repository;

import com.bmp.rewards.internal.entity.LoyaltyAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, UUID> {
}
