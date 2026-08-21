package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One salon a coupon is restricted to (rewards_schema.coupon_salon, V003).
 *
 * <p>Only populated when {@code salon_scope = 'selected_salons'}. V002's single nullable
 * {@code coupon.salon_id} still means "this one salon" for rows that predate V003; this table
 * exists because "these four salons in Indiranagar" is a perfectly normal campaign and one
 * column cannot hold it.
 */
@Entity
@Table(name = "coupon_salon", schema = "rewards_schema")
public class CouponSalon {

    @Id
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    /** Logical ref -> salon_schema.salon. */
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CouponSalon() {} // JPA

    public CouponSalon(UUID couponId, UUID salonId) {
        this.id = UuidV7.generate();
        this.couponId = couponId;
        this.salonId = salonId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCouponId() { return couponId; }
    public UUID getSalonId() { return salonId; }
    public Instant getCreatedAt() { return createdAt; }
}
