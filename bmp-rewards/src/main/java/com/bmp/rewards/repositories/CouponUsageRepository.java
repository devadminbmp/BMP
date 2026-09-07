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

    /**
     * ANY coupon already redeemed on this booking, whichever one. Session 55.
     *
     * <p>Note how this differs from the method above, and why both exist. That one is keyed on
     * {@code (coupon_id, booking_id)} and makes retrying the SAME coupon idempotent — right for a
     * Feign timeout. It says nothing about a DIFFERENT coupon on the same booking, which is the
     * rule customers actually experience: one code per order.
     *
     * <p>The database enforces it too ({@code uq_coupon_usage_one_per_booking}, V005). This exists
     * so the refusal is a sentence the customer can read rather than a constraint violation.
     */
    Optional<CouponUsage> findFirstByBookingId(UUID bookingId);

    /** Used when a booking is cancelled and its coupon use should be given back. */
    List<CouponUsage> findByBookingId(UUID bookingId);
}
