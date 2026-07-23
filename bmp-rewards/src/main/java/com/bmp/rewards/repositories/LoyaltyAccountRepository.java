package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.LoyaltyAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, UUID> {
}
