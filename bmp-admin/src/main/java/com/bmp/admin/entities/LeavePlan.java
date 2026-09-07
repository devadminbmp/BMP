package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The org-wide leave allowance for one ROLE, one TYPE, one financial year. V016, Session 65.
 *
 * <p>"A support agent gets 12 casual days in 2026-27." The DEFAULT everybody in that role inherits,
 * including the person hired tomorrow that nobody remembered to configure.
 *
 * <p>{@link LeaveEntitlement} is the per-person override. Resolution is entitlement → plan → zero,
 * and {@code com.bmp.admin.services.LeavePlanService} is the only place that resolution is written.
 *
 * @see LeaveEntitlement
 */
@Entity
@Table(name = "leave_plan", schema = "admin_schema")
@Getter
public class LeavePlan {

    @Id
    private UUID id;

    /** A console role string — see RoleHierarchy. No FK: roles are constants, not rows. */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    /** casual | sick | annual | comp_off. Never `unpaid` — there is no ceiling to allocate. */
    @Column(name = "leave_type", nullable = false, length = 20)
    private String leaveType;

    /** The financial year's START year: 2026 means Apr 2026 – Mar 2027. See LeaveYear. */
    @Column(name = "fy_start_year", nullable = false)
    private int fyStartYear;

    /** NUMERIC(4,1) — half-days are real, and binary floating point is not exact enough for pay. */
    @Setter
    @Column(name = "days_allowed", nullable = false, precision = 4, scale = 1)
    private BigDecimal daysAllowed;

    /** How much may roll into next year. Defaults to zero — carry-forward is an accruing liability. */
    @Setter
    @Column(name = "carry_forward_max", nullable = false, precision = 4, scale = 1)
    private BigDecimal carryForwardMax = BigDecimal.ZERO;

    @Setter
    @Column(name = "set_by_staff_id")
    private UUID setByStaffId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeavePlan() {} // JPA

    public LeavePlan(String role, String leaveType, int fyStartYear,
                     BigDecimal daysAllowed, BigDecimal carryForwardMax, UUID setByStaffId) {
        this.id = UuidV7.generate();
        this.role = role;
        this.leaveType = leaveType;
        this.fyStartYear = fyStartYear;
        this.daysAllowed = daysAllowed;
        this.carryForwardMax = carryForwardMax == null ? BigDecimal.ZERO : carryForwardMax;
        this.setByStaffId = setByStaffId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void touch() { this.updatedAt = Instant.now(); }
}
