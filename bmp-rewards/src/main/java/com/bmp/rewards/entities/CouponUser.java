package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One customer a coupon is restricted to (rewards_schema.coupon_user, V003).
 *
 * <p>Only populated when {@code audience_type = 'selected_users'}. This is the table support
 * writes to: a goodwill coupon is for one person, and redemption checks membership here — so a
 * code that leaks onto a deals forum is worthless to everyone except the customer it was meant
 * for.
 */
@Entity
@Table(name = "coupon_user", schema = "rewards_schema")
public class CouponUser {

    @Id
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    /** Logical ref -> user_schema.users. Cross-schema FKs are not used in this repo. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CouponUser() {} // JPA

    public CouponUser(UUID couponId, UUID userId) {
        this.id = UuidV7.generate();
        this.couponId = couponId;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCouponId() { return couponId; }
    public UUID getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}
