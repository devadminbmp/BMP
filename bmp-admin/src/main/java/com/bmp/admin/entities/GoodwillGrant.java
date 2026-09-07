package com.bmp.admin.entities;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One act of goodwill against one booking, other than a refund. V012, Session 59.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT EXISTS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code GoodwillCapService} enforces "never give back more than the customer paid" by subtracting
 * what has already gone out. Before this table it could only see REFUNDS, because those are
 * recorded on the booking in bmp-booking. Coupons, wallet credits and waived fees were invisible,
 * so a ₹600 booking could take two ₹500 coupons and each one passed the cap.
 *
 * <p>Written by the act of GRANTING, not the act of approving — goodwill inside somebody's own
 * authority never produces an approval request, and those are the majority.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * IMMUTABLE ON PURPOSE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * No setters. A grant is a historical fact about money that left the business; correcting one means
 * writing a compensating row, not editing the original. Anything that can be edited is something an
 * audit cannot rely on.
 *
 * <p>Refunds are excluded by a CHECK constraint in V012, not by convention — see that migration for
 * why double-counting them would halve every customer's real ceiling.
 */
@Entity
@Table(name = "goodwill_grant", schema = "admin_schema")
@Getter
public class GoodwillGrant {

    @Id
    private UUID id;

    /** In bmp-booking's schema. Not an FK — see V012 on cross-service integrity. */
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    /** One of GoodwillCapService's capped actions, never {@code refund.issue}. */
    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(name = "value_paise", nullable = false)
    private long valuePaise;

    /** Null when it was within the actor's own authority — the common case. */
    @Column(name = "approval_request_id")
    private UUID approvalRequestId;

    @Column(name = "granted_by_staff_id", nullable = false)
    private UUID grantedByStaffId;

    @Column(name = "granted_by_role", nullable = false, length = 30)
    private String grantedByRole;

    /** Coupon code, wallet transaction id — whatever ties this row to what the customer got. */
    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GoodwillGrant() {}

    /**
     * The only way to make one.
     *
     * <p>A factory rather than a builder because every field except {@code reference} and
     * {@code approvalRequestId} is mandatory, and a builder would make it possible to construct a
     * grant with no amount and no owner — which the database would reject at flush time, far from
     * the code that caused it.
     */
    public static GoodwillGrant of(UUID bookingId, String actionType, long valuePaise,
                                    UUID approvalRequestId, UUID grantedByStaffId,
                                    String grantedByRole, String reference) {
        GoodwillGrant g = new GoodwillGrant();
        g.id = UUID.randomUUID();
        g.bookingId = bookingId;
        g.actionType = actionType;
        g.valuePaise = valuePaise;
        g.approvalRequestId = approvalRequestId;
        g.grantedByStaffId = grantedByStaffId;
        g.grantedByRole = grantedByRole;
        g.reference = reference;
        g.createdAt = Instant.now();
        return g;
    }
}
