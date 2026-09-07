package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Time off for a BMP staff member. V011, Session 59.
 *
 * <h2>The same shape as stylist leave, on purpose</h2>
 * {@code salon_schema.stylist_leave_request} (V023) already models request → decide, and copying
 * its shape means one set of rules to learn and one place a bug gets fixed. Two leave systems in one
 * platform that behave differently is a support question every single month.
 *
 * <h2>The one important difference</h2>
 * Approved staff leave flips {@code accepting_tickets} OFF for the duration. Stylist leave removes
 * bookable slots; staff leave must remove somebody from the ASSIGNMENT ROTATION — otherwise the
 * queue keeps handing tickets to a person on holiday and a customer waits a week for a first reply.
 * That is handled in {@code StaffLeaveService}, not here, because it touches another table.
 */
@Entity
@Table(name = "staff_leave", schema = "admin_schema")
@Getter
public class StaffLeave {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String CANCELLED = "cancelled";

    @Id
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    /** casual | sick | unpaid | comp_off */
    @Column(name = "leave_type", nullable = false, length = 20)
    private String leaveType;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    /**
     * {@code morning} | {@code afternoon}, or null for a whole day.
     *
     * <p>Modelled because a half-day is the commonest leave anybody takes, and recording it as a
     * full day overstates the gap in cover — which then reads as a staffing problem on the team
     * screen when it is somebody leaving at lunch.
     */
    @Column(name = "half_day", length = 10)
    private String halfDay;

    @Column(name = "reason", length = 500)
    private String reason;

    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status = PENDING;

    @Setter
    @Column(name = "decided_by_staff_id")
    private UUID decidedByStaffId;

    @Setter
    @Column(name = "decided_at")
    private Instant decidedAt;

    @Setter
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StaffLeave() {} // JPA

    public StaffLeave(UUID staffId, String leaveType, LocalDate startsOn, LocalDate endsOn,
                       String halfDay, String reason) {
        this.id = UuidV7.generate();
        this.staffId = staffId;
        this.leaveType = leaveType;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.halfDay = halfDay;
        this.reason = reason;
        this.status = PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public boolean isPending() { return PENDING.equals(status); }
    public boolean isApproved() { return APPROVED.equals(status); }

    /**
     * CALENDAR SPAN — how many days this absence touches. A half-day still touches one day.
     *
     * <p>This is the right number for COVER: "is this person around on Tuesday" does not care that
     * they are leaving at lunch. It is the WRONG number for a balance — see {@link #daysCharged()}.
     */
    public long days() {
        return java.time.temporal.ChronoUnit.DAYS.between(startsOn, endsOn) + 1;
    }

    /**
     * BALANCE COST — what this deducts from an entitlement. Session 65.
     *
     * <p>A half-day costs 0.5, not 1. Before entitlements existed (V016) nothing subtracted from
     * anything, so {@link #days()} being coarse was invisible. The moment a balance is shown,
     * charging a full day for a half-day understates everybody's remaining leave — quietly, in the
     * employer's favour, and compounding every time somebody leaves at lunch.
     *
     * <p>BigDecimal rather than double because this is arithmetic people are paid against, and
     * {@code 0.1 + 0.2 != 0.3} in binary floating point. The column behind it is NUMERIC(4,1) for
     * the same reason.
     *
     * <p>Guarded on {@code startsOn == endsOn} as well as on halfDay: the database already forbids
     * a half-day spanning a range (chk_leave_half_single_day, V011), and relying on a constraint in
     * another file to keep an arithmetic method honest is how the two drift apart.
     */
    public java.math.BigDecimal daysCharged() {
        if (halfDay != null && !halfDay.isBlank() && startsOn.equals(endsOn)) {
            return new java.math.BigDecimal("0.5");
        }
        return java.math.BigDecimal.valueOf(days());
    }

    /**
     * The part of this leave that falls INSIDE a window, in balance days. Session 65.
     *
     * <p>For a leave running 30 March to 2 April, asked about FY 2025 (to 31 March), this returns
     * 2 — and asked about FY 2026 (from 1 April) it returns 2 as well. The four days are charged
     * once each, to the year they actually happened in.
     *
     * <p>Without this, a straddling leave is charged either twice or not at all depending on how
     * the query is written, and "not at all" is what the old whole-leave-inside-window query did:
     * silently, in the employee's favour, once a year, at the boundary nobody tests.
     *
     * <p>A half-day cannot straddle anything — the database forbids it (chk_leave_half_single_day)
     * — so it is either wholly in the window or wholly out, and returning its 0.5 unchanged is
     * correct rather than a special case.
     *
     * @return zero if the leave does not touch the window at all.
     */
    public java.math.BigDecimal daysChargedWithin(LocalDate from, LocalDate to) {
        LocalDate first = startsOn.isBefore(from) ? from : startsOn;
        LocalDate last  = endsOn.isAfter(to)     ? to   : endsOn;
        if (first.isAfter(last)) return java.math.BigDecimal.ZERO;

        if (halfDay != null && !halfDay.isBlank() && startsOn.equals(endsOn)) {
            return new java.math.BigDecimal("0.5");
        }
        return java.math.BigDecimal.valueOf(
                java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1);
    }

    /** True when this leave covers the given date — used to decide who is off today. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public void decide(String newStatus, UUID byStaffId, String note) {
        this.status = newStatus;
        this.decidedByStaffId = byStaffId;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
        this.updatedAt = this.decidedAt;
    }
}
