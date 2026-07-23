package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {
    List<CouponUsage> findByCouponIdAndUserId(UUID couponId, UUID userId);
    long countByCouponId(UUID couponId);
}
