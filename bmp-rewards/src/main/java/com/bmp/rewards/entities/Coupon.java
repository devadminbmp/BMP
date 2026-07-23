package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.coupon.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "coupon", schema = "rewards_schema")
public class Coupon {

    @Id
    private UUID id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;
    @Column(name = "coupon_type", nullable = false, length = 20)
    private String couponType;
    @Column(name = "salon_id")
    private UUID salonId;
    @Column(name = "commission_base", nullable = false, length = 15)
    private String commissionBase;
    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;
    @Column(name = "value", nullable = false)
    private long value;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "min_spend_paise", nullable = false)
    private Money minSpendPaise;
    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;
    @Column(name = "total_usage_cap")
    private int totalUsageCap;
    @Column(name = "active_from", nullable = false)
    private Instant activeFrom;
    @Column(name = "active_to", nullable = false)
    private Instant activeTo;
    @Column(name = "allows_wallet_stacking", nullable = false)
    private boolean allowsWalletStacking;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Coupon() {} // JPA

    public Coupon(String code, String couponType, UUID salonId, String commissionBase, String discountType, long value, Money minSpendPaise, int perUserLimit, int totalUsageCap, Instant activeFrom, Instant activeTo, boolean allowsWalletStacking) {
        this.id = UuidV7.generate();
        this.code = code;
        this.couponType = couponType;
        this.salonId = salonId;
        this.commissionBase = commissionBase;
        this.discountType = discountType;
        this.value = value;
        this.minSpendPaise = minSpendPaise;
        this.perUserLimit = perUserLimit;
        this.totalUsageCap = totalUsageCap;
        this.activeFrom = activeFrom;
        this.activeTo = activeTo;
        this.allowsWalletStacking = allowsWalletStacking;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getCouponType() { return couponType; }
    public UUID getSalonId() { return salonId; }
    public String getCommissionBase() { return commissionBase; }
    public String getDiscountType() { return discountType; }
    public long getValue() { return value; }
    public Money getMinSpendPaise() { return minSpendPaise; }
    public int getPerUserLimit() { return perUserLimit; }
    public int getTotalUsageCap() { return totalUsageCap; }
    public Instant getActiveFrom() { return activeFrom; }
    public Instant getActiveTo() { return activeTo; }
    public boolean isAllowsWalletStacking() { return allowsWalletStacking; }
    public Instant getCreatedAt() { return createdAt; }
}
