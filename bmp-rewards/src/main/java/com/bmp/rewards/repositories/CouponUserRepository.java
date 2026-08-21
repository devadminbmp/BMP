package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CouponUserRepository extends JpaRepository<CouponUser, UUID> {

    List<CouponUser> findByCouponId(UUID couponId);

    /**
     * The redemption check: is THIS customer on the list for THIS coupon?
     *
     * <p>This single call is what makes a leaked support coupon worthless to a stranger.
     */
    boolean existsByCouponIdAndUserId(UUID couponId, UUID userId);

    /** "What has this customer been given?" — the first question when they ask about a code. */
    List<CouponUser> findByUserId(UUID userId);
}
