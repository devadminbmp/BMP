package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.LoyaltyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, UUID> {
}
