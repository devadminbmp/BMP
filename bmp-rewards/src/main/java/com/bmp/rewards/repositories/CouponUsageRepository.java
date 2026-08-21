package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {
    List<CouponUsage> findByCouponIdAndUserId(UUID couponId, UUID userId);
    long countByCouponId(UUID couponId);

    // ---- Session 22: redemption -------------------------------------------------------------

    /**
     * Idempotency for redemption.
     *
     * <p>bmp-booking can retry a booking for reasons that have nothing to do with coupons — a
     * Feign timeout, a client resend. Without this check one booking would consume two uses of
     * the customer's allowance: our bug, their complaint.
     */
    Optional<CouponUsage> findByCouponIdAndBookingId(UUID couponId, UUID bookingId);

    /** Used when a booking is cancelled and its coupon use should be given back. */
    List<CouponUsage> findByBookingId(UUID bookingId);
}
