package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.checkout_discount.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "checkout_discount", schema = "rewards_schema")
public class CheckoutDiscount {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "coupon_id")
    private UUID couponId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "discount_paise", nullable = false)
    private Money discountPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "commission_base_paise", nullable = false)
    private Money commissionBasePaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "final_charge_paise", nullable = false)
    private Money finalChargePaise;
    @Column(name = "coupon_restore_policy", nullable = false, length = 30)
    private String couponRestorePolicy;

    protected CheckoutDiscount() {} // JPA

    public CheckoutDiscount(UUID bookingId, UUID couponId, Money discountPaise, Money commissionBasePaise, Money finalChargePaise, String couponRestorePolicy) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.couponId = couponId;
        this.discountPaise = discountPaise;
        this.commissionBasePaise = commissionBasePaise;
        this.finalChargePaise = finalChargePaise;
        this.couponRestorePolicy = couponRestorePolicy;

    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getCouponId() { return couponId; }
    public Money getDiscountPaise() { return discountPaise; }
    public Money getCommissionBasePaise() { return commissionBasePaise; }
    public Money getFinalChargePaise() { return finalChargePaise; }
    public String getCouponRestorePolicy() { return couponRestorePolicy; }
}
