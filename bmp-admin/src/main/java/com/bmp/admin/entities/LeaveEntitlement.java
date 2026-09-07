package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ONE PERSON'S leave allowance for one type in one financial year. V016, Session 65.
 *
 * <p>Overrides {@link LeavePlan}. It exists because real HR is exceptions: somebody negotiated 18
 * days, somebody joined in November on a pro-rated allowance, somebody was granted extra after a
 * hard quarter. A system with only role defaults forces those to be faked as new roles.
 *
 * <h2>{@code source} is the column that makes a rollout safe</h2>
 * <ul>
 *   <li>{@code plan} — materialised from the role default. Safe for a future rollout to refresh.</li>
 *   <li>{@code override} — somebody decided this individually. A rollout must leave it alone.</li>
 * </ul>
 *
 * Without it, a negotiated 18 days and a copied 12 days are indistinguishable, and the first
 * org-wide plan change either overwrites the negotiation or skips everybody to avoid the risk.
 */
@Entity
@Table(name = "leave_entitlement", schema = "admin_schema")
@Getter
public class LeaveEntitlement {

    /** Copied from the role's plan. A rollout may refresh this. */
    public static final String SOURCE_PLAN = "plan";
    /** Set for this person deliberately. A rollout must not touch it. */
    public static final String SOURCE_OVERRIDE = "override";

    @Id
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    /** casual | sick | annual | comp_off. */
    @Column(name = "leave_type", nullable = false, length = 20)
    private String leaveType;

    /** Financial year START year — see LeaveYear. */
    @Column(name = "fy_start_year", nullable = false)
    private int fyStartYear;

    @Setter
    @Column(name = "days_allowed", nullable = false, precision = 4, scale = 1)
    private BigDecimal daysAllowed;

    @Setter
    @Column(name = "source", nullable = false, length = 10)
    private String source = SOURCE_PLAN;

    /** Why the exception exists. An override nobody can explain later is an override nobody can defend. */
    @Setter
    @Column(name = "note", length = 300)
    private String note;

    @Setter
    @Column(name = "set_by_staff_id")
    private UUID setByStaffId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaveEntitlement() {} // JPA

    public LeaveEntitlement(UUID staffId, String leaveType, int fyStartYear, BigDecimal daysAllowed,
                            String source, String note, UUID setByStaffId) {
        this.id = UuidV7.generate();
        this.staffId = staffId;
        this.leaveType = leaveType;
        this.fyStartYear = fyStartYear;
        this.daysAllowed = daysAllowed;
        this.source = source == null ? SOURCE_PLAN : source;
        this.note = note;
        this.setByStaffId = setByStaffId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public boolean isOverride() { return SOURCE_OVERRIDE.equals(source); }

    public void touch() { this.updatedAt = Instant.now(); }
}
