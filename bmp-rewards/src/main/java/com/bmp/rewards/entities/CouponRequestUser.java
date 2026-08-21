package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A named recipient on a coupon request — the same shape as {@link CouponUser}, one step earlier
 * in the flow.
 *
 * <p>Kept separate rather than writing straight into {@code coupon_user}: those rows are what
 * redemption checks membership against, so a row there for a coupon that was never approved
 * would be a recipient of nothing at best, and a bug waiting to be found at worst. The request's
 * recipients are copied across only when the coupon is actually minted.
 */
@Entity
@Table(name = "coupon_request_user", schema = "rewards_schema")
@Getter
public class CouponRequestUser {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CouponRequestUser() {} // JPA

    public CouponRequestUser(UUID requestId, UUID userId) {
        this.id = UuidV7.generate();
        this.requestId = requestId;
        this.userId = userId;
        this.createdAt = Instant.now();
    }
}
