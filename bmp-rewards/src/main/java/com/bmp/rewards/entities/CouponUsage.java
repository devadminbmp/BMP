package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.coupon_usage.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "coupon_usage", schema = "rewards_schema")
public class CouponUsage {

    @Id
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "discount_applied_paise", nullable = false)
    private Money discountAppliedPaise;
    @Column(name = "was_refunded", nullable = false)
    private boolean wasRefunded;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CouponUsage() {} // JPA

    public CouponUsage(UUID couponId, UUID userId, UUID bookingId, Money discountAppliedPaise, boolean wasRefunded) {
        this.id = UuidV7.generate();
        this.couponId = couponId;
        this.userId = userId;
        this.bookingId = bookingId;
        this.discountAppliedPaise = discountAppliedPaise;
        this.wasRefunded = wasRefunded;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCouponId() { return couponId; }
    public UUID getUserId() { return userId; }
    public UUID getBookingId() { return bookingId; }
    public Money getDiscountAppliedPaise() { return discountAppliedPaise; }
    public boolean isWasRefunded() { return wasRefunded; }
    public Instant getCreatedAt() { return createdAt; }
}
