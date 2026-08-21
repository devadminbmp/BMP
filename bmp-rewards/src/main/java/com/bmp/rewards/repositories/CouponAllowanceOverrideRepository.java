package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.CouponAllowanceOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-staff coupon allowance exceptions. Absent row = the platform default applies. */
public interface CouponAllowanceOverrideRepository extends JpaRepository<CouponAllowanceOverride, UUID> {

    Optional<CouponAllowanceOverride> findByStaffId(UUID staffId);

    /**
     * Every override, newest first — the "who has a bespoke allowance, and why" screen.
     *
     * <p>Worth having as a list an admin can look at, not just a lookup: a set of exceptions
     * nobody ever reviews stops being a set of exceptions and becomes the real policy.
     */
    List<CouponAllowanceOverride> findAllByOrderByCreatedAtDesc();
}
