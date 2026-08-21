package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponSalon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CouponSalonRepository extends JpaRepository<CouponSalon, UUID> {

    List<CouponSalon> findByCouponId(UUID couponId);

    /** Redemption check for a scoped campaign: is this booking's salon in the list? */
    boolean existsByCouponIdAndSalonId(UUID couponId, UUID salonId);
}
