package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A request for a coupon the requester cannot issue themselves.
 *
 * <p>Session 31 (V004). Two kinds of person raise these, and the queue serves both:
 * <ul>
 *   <li><b>Support</b>, when the goodwill they need is above their per-coupon limit or their
 *       rolling allowance. Without this, the agent hits a 403 and the conversation with the
 *       customer stops there.</li>
 *   <li><b>Salon owners</b>, who can never issue directly — a coupon can be funded out of BMP's
 *       commission ({@code commission_base}), so "who pays for this?" is not theirs to answer.</li>
 * </ul>
 *
 * <p>The proposed coupon is stored as columns rather than a JSON blob because an admin filters
 * this queue by value and expiry, and you cannot index a blob. It is a deliberate duplication of
 * {@link Coupon}'s shape; the alternative — pointing at a draft coupon row — would mean
 * unapproved coupons living in the same table as live ones, which is exactly the kind of
 * "it's only a draft" state that eventually gets redeemed.
 */
@Entity
@Table(name = "coupon_request", schema = "rewards_schema")
@Getter
public class CouponRequest {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_EXPIRED = "expired";

    public static final String REQUESTER_STAFF = "staff";
    public static final String REQUESTER_SALON_OWNER = "salon_owner";

    @Id
    private UUID id;

    @Column(name = "request_ref", nullable = false, length = 24)
    private String requestRef;

    @Column(name = "requester_type", nullable = false, length = 20)
    private String requesterType;
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;
    @Setter
    @Column(name = "requester_name", length = 160)
    private String requesterName;
    @Setter
    @Column(name = "requester_email", length = 160)
    private String requesterEmail;
    /** Resolved at raise time — see CouponRequestService. Nullable; staff have none on file. */
    @Setter
    @Column(name = "requester_phone", length = 20)
    private String requesterPhone;
    @Setter
    @Column(name = "requester_role", length = 30)
    private String requesterRole;
    @Setter
    @Column(name = "salon_id")
    private UUID salonId;

    @Setter
    @Column(name = "justification", nullable = false)
    private String justification;
    @Setter
    @Column(name = "issued_for_ticket_id")
    private UUID issuedForTicketId;

    // ---- what is being asked for ------------------------------------------------------------
    @Setter @Column(name = "proposed_name", length = 120) private String proposedName;
    @Setter @Column(name = "audience_type", nullable = false, length = 20) private String audienceType;
    @Setter @Column(name = "salon_scope", nullable = false, length = 20) private String salonScope;
    @Setter @Column(name = "discount_type", nullable = false, length = 10) private String discountType;
    @Setter @Column(name = "value", nullable = false) private long value;
    @Setter @Column(name = "max_discount_paise") private Long maxDiscountPaise;
    @Setter @Column(name = "min_spend_paise", nullable = false) private long minSpendPaise;
    @Setter @Column(name = "per_user_limit", nullable = false) private int perUserLimit = 1;
    @Setter @Column(name = "total_usage_cap") private Integer totalUsageCap;
    @Setter @Column(name = "active_from", nullable = false) private Instant activeFrom;
    @Setter @Column(name = "active_to", nullable = false) private Instant activeTo;
    @Setter @Column(name = "commission_base", nullable = false, length = 15) private String commissionBase = "post_discount";

    // ---- the decision -------------------------------------------------------------------------
    @Setter @Column(name = "status", nullable = false, length = 20) private String status = STATUS_PENDING;
    @Setter @Column(name = "decided_by_staff_id") private UUID decidedByStaffId;
    @Setter @Column(name = "decided_by_email", length = 160) private String decidedByEmail;
    @Setter @Column(name = "decided_at") private Instant decidedAt;
    @Setter @Column(name = "decision_note") private String decisionNote;

    /**
     * What the admin actually granted, when it differs from what was asked.
     *
     * <p>Null means "exactly as requested". These exist so an approver can say "you asked for
     * ₹2,000, here's ₹800" — because an approver who can only say yes or no says <b>no</b>, and
     * the alternative (reject, then ask the agent to re-request at a lower figure) is a round
     * trip nobody makes. In practice they'd just approve the ₹2,000.
     */
    @Setter @Column(name = "approved_value") private Long approvedValue;
    @Setter @Column(name = "approved_max_discount_paise") private Long approvedMaxDiscountPaise;
    @Setter @Column(name = "approved_active_to") private Instant approvedActiveTo;

    /** Set when approval mints the coupon — the link that makes this an audit trail. */
    @Setter @Column(name = "created_coupon_id") private UUID createdCouponId;

    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected CouponRequest() {} // JPA

    public CouponRequest(String requestRef, String requesterType, UUID requesterId, String justification) {
        this.id = UuidV7.generate();
        this.requestRef = requestRef;
        this.requesterType = requesterType;
        this.requesterId = requesterId;
        this.justification = justification;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    /** The granted figure, falling back to what was asked. Used when minting the coupon. */
    public long effectiveValue() {
        return approvedValue != null ? approvedValue : value;
    }

    public Long effectiveMaxDiscountPaise() {
        return approvedMaxDiscountPaise != null ? approvedMaxDiscountPaise : maxDiscountPaise;
    }

    public Instant effectiveActiveTo() {
        return approvedActiveTo != null ? approvedActiveTo : activeTo;
    }
}
