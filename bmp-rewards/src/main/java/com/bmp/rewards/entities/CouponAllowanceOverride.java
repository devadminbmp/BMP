package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One staff member's bespoke coupon allowance — an exception to the platform default.
 *
 * <p>Session 31 (V004). Absent row means the default applies, which is the case for almost
 * everyone. This exists for the two real situations: a senior agent trusted with more, and
 * someone new who should have less for a fortnight.
 *
 * <p><b>Why data and not a role.</b> "Senior support" as a fourth staff role would mean a new
 * row in the permission matrix, new branches in every place roles are checked, and a new thing
 * to explain — all to change one number. Roles should stay few enough that a person can hold
 * them in their head ({@code StaffPermission}'s javadoc makes the same argument). An exception
 * to a default belongs in a table.
 *
 * <p>Every null field means "use the platform default for this one", so raising a single limit
 * doesn't require restating the others — and doesn't silently freeze them at today's values if
 * the defaults later change.
 */
@Entity
@Table(name = "coupon_allowance_override", schema = "rewards_schema")
@Getter
public class CouponAllowanceOverride {

    @Id
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Setter
    @Column(name = "staff_email", length = 160)
    private String staffEmail;

    @Setter
    @Column(name = "max_count_per_period")
    private Integer maxCountPerPeriod;

    @Setter
    @Column(name = "max_paise_per_period")
    private Long maxPaisePerPeriod;

    @Setter
    @Column(name = "max_flat_paise")
    private Long maxFlatPaise;

    @Setter
    @Column(name = "max_percent_bps")
    private Integer maxPercentBps;

    /**
     * Why this person is different. Required — an unexplained exception is indistinguishable
     * from a mistake six months later, and this is the field an auditor reads first.
     */
    @Setter
    @Column(name = "reason", nullable = false)
    private String reason;

    /**
     * Optional. A temporary raise ("Diwali week") that never expires quietly becomes permanent,
     * and nobody remembers to remove it — so the expiry is part of the grant, not a follow-up
     * task. Null means indefinite, which should be rare and deliberate.
     */
    @Setter
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Setter
    @Column(name = "updated_by_staff_id")
    private UUID updatedByStaffId;

    @Setter
    @Column(name = "updated_by_email", length = 160)
    private String updatedByEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CouponAllowanceOverride() {} // JPA

    public CouponAllowanceOverride(UUID staffId, String staffEmail, String reason) {
        this.id = UuidV7.generate();
        this.staffId = staffId;
        this.staffEmail = staffEmail;
        this.reason = reason;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
