package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, UUID> {
    Optional<CouponPolicy> findByPolicyKey(String policyKey);
}
