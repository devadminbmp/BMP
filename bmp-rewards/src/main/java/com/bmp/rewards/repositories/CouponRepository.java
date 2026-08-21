package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCode(String code);

    /**
     * Session 22: the same lookup, holding a row lock for the rest of the transaction.
     *
     * <p>Used only by redemption. Two customers claiming the last use of a limited coupon at
     * the same instant would both pass a count-then-insert check and both get the discount —
     * classic, and invisible until the offer is popular enough to matter.
     *
     * <p>The lock costs almost nothing here: one row, held for the milliseconds it takes to
     * count usage and insert. The alternative — tolerating a small overshoot — is defensible for
     * a ₹50 marketing code and indefensible for a ₹5,000 launch offer, and making it conditional
     * on the coupon would mean the risky path is the one nobody ever tests.
     *
     * <p>It serialises redemptions of the SAME coupon, not of all coupons. A viral code is the
     * one case where that could queue — acceptable, since the alternative is giving away more
     * than was budgeted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE upper(c.code) = upper(:code)")
    Optional<Coupon> findByCodeForUpdate(@Param("code") String code);

    /**
     * Everything one staff member has issued since a cut-off — the input to their allowance.
     *
     * <p>Session 31. <b>Derived, not counted into a column.</b> A running total is a second
     * source of truth that drifts the first time a coupon is created outside the normal path
     * (a migration, a manual fix, a bug), and it drifts silently in the direction of letting
     * people spend more. Recomputing from the coupons themselves cannot disagree with reality.
     *
     * <p>Cheap by construction: the window is days, one agent, and
     * {@code idx_coupon_creator (created_by_staff_id, created_at DESC)} already exists from V003.
     *
     * <p>Revoked coupons are included deliberately — see {@code CouponAllowanceService}.
     */
    List<Coupon> findByCreatedByStaffIdAndCreatedAtAfter(UUID createdByStaffId, Instant since);
}
