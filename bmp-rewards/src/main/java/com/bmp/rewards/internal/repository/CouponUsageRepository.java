package com.bmp.rewards.internal.repository;

import com.bmp.rewards.internal.entity.CouponUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {
    List<CouponUsage> findByCouponIdAndUserId(UUID couponId, UUID userId);
    long countByCouponId(UUID couponId);
}
