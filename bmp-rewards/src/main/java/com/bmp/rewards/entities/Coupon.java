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

    // ---- V003: targeting, status and provenance -------------------------------------------

    /** all_users | selected_users | new_users | referred_users — who may USE it. */
    @Column(name = "audience_type", nullable = false, length = 20)
    private String audienceType;

    /** all_salons | selected_salons. The list lives in coupon_salon. */
    @Column(name = "salon_scope", nullable = false, length = 20)
    private String salonScope;

    /** What staff recognise in a list; `code` is what customers type. */
    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "description")
    private String description;

    /**
     * draft | active | paused | expired | revoked.
     *
     * <p>Separate from the active window on purpose: a live campaign going wrong must be
     * stoppable NOW, without editing dates and without deleting a coupon customers already hold.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Required for percentage coupons — without it a ₹8,000 package becomes free. */
    @Column(name = "max_discount_paise")
    private Long maxDiscountPaise;

    @Column(name = "created_by_staff_id")
    private UUID createdByStaffId;

    /** Denormalised so the row stays readable after that person leaves. */
    @Column(name = "created_by_email", length = 160)
    private String createdByEmail;

    /** The role AT THE TIME — what makes "support issued this" auditable later. */
    @Column(name = "created_by_role", length = 20)
    private String createdByRole;

    /** Required for support-issued coupons: the complaint this settles. */
    @Column(name = "issued_for_ticket_id")
    private UUID issuedForTicketId;

    @Column(name = "issue_reason")
    private String issueReason;

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
        // Defaults matching V003's column defaults, so rows created through the pre-V003
        // constructor behave exactly as they did before.
        this.audienceType = "all_users";
        this.salonScope = "all_salons";
        this.status = "active";
    }

    /**
     * V003 constructor — everything the console sets when issuing a coupon.
     *
     * <p>The pre-V003 constructor above is kept so existing call sites compile unchanged.
     */
    public Coupon(String code, String name, String description, String couponType, UUID salonId,
                  String commissionBase, String discountType, long value, Long maxDiscountPaise,
                  Money minSpendPaise, int perUserLimit, int totalUsageCap,
                  Instant activeFrom, Instant activeTo, boolean allowsWalletStacking,
                  String audienceType, String salonScope, String status,
                  UUID createdByStaffId, String createdByEmail, String createdByRole,
                  UUID issuedForTicketId, String issueReason) {
        this(code, couponType, salonId, commissionBase, discountType, value, minSpendPaise,
                perUserLimit, totalUsageCap, activeFrom, activeTo, allowsWalletStacking);
        this.name = name;
        this.description = description;
        this.maxDiscountPaise = maxDiscountPaise;
        this.audienceType = audienceType;
        this.salonScope = salonScope;
        this.status = status;
        this.createdByStaffId = createdByStaffId;
        this.createdByEmail = createdByEmail;
        this.createdByRole = createdByRole;
        this.issuedForTicketId = issuedForTicketId;
        this.issueReason = issueReason;
    }

    /**
     * Stop a live coupon without deleting it.
     *
     * <p>Deleting would strand customers holding the code with no explanation; pausing lets
     * redemption fail with a reason and keeps the history intact.
     */
    public void setStatus(String status) { this.status = status; }

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

    // ---- V003 ------------------------------------------------------------------------------
    public String getAudienceType() { return audienceType; }
    public String getSalonScope() { return salonScope; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Long getMaxDiscountPaise() { return maxDiscountPaise; }
    public UUID getCreatedByStaffId() { return createdByStaffId; }
    public String getCreatedByEmail() { return createdByEmail; }
    public String getCreatedByRole() { return createdByRole; }
    public UUID getIssuedForTicketId() { return issuedForTicketId; }
    public String getIssueReason() { return issueReason; }

    /** Live right now: status allows it AND we're inside the active window. */
    public boolean isRedeemableNow() {
        Instant now = Instant.now();
        return "active".equalsIgnoreCase(status)
                && !now.isBefore(activeFrom)
                && now.isBefore(activeTo);
    }
}
