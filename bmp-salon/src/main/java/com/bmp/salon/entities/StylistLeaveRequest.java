package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A stylist's request for time off, and the salon's answer. V023 (Session 49).
 *
 * <h2>This is the paperwork, not the mechanism</h2>
 * The booking algorithm never reads this table. It reads {@code stylist_availability}, which has
 * carried {@code rule_type='leave'} since V003 and is already subtracted from a stylist's free
 * time by {@code AvailabilityService.breakAndLeaveWindows}.
 *
 * <p>So <b>approving</b> a request writes availability rows, and those rows are what removes the
 * stylist from bookings on the day. That separation is the point: a pending request must not
 * block anything, because blocking on request means a request the owner would have declined has
 * already cost the salon a day of bookings.
 *
 * <h2>Dates are inclusive at both ends</h2>
 * {@code startsOn = endsOn} is a single day's leave, which is the commonest case. A half-open
 * interval would be more consistent with the closure table but would mean a stylist asking for
 * "the 14th" has to enter the 15th, and they will not.
 */
@Entity
@Table(name = "stylist_leave_request", schema = "salon_schema")
@Getter
public class StylistLeaveRequest {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String DECLINED = "declined";
    public static final String CANCELLED = "cancelled";

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false, updatable = false)
    private UUID stylistId;
    @Column(name = "salon_id", nullable = false, updatable = false)
    private UUID salonId;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;
    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    /** Null for a whole-day leave — the usual case. Both set for a partial day. */
    @Column(name = "start_time")
    private LocalTime startTime;
    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "leave_type", nullable = false, length = 20)
    private String leaveType;
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StylistLeaveRequest() {} // JPA

    public StylistLeaveRequest(UUID stylistId, UUID salonId, LocalDate startsOn, LocalDate endsOn,
                                LocalTime startTime, LocalTime endTime,
                                String leaveType, String reason) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.salonId = salonId;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.startTime = startTime;
        this.endTime = endTime;
        this.leaveType = (leaveType == null || leaveType.isBlank()) ? "other" : leaveType;
        this.reason = reason;
        this.status = PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isPending() { return PENDING.equals(status); }
    public boolean isApproved() { return APPROVED.equals(status); }

    /** Whole-day leave when no times were given. Drives which availability row gets written. */
    public boolean isWholeDay() { return startTime == null || endTime == null; }

    /** How many calendar days this covers. Inclusive, so a single day is 1, never 0. */
    public long days() { return java.time.temporal.ChronoUnit.DAYS.between(startsOn, endsOn) + 1; }

    /**
     * The salon answers.
     *
     * <p>Refuses to re-decide, deliberately. An approved leave has already written availability
     * rows and may have caused bookings to be declined or moved; flipping it back to declined
     * with a second call would leave those rows behind and the stylist unbookable for a leave
     * that no longer exists. Undoing an approval is {@link #cancel()}, which cleans up.
     */
    public void decide(boolean approve, UUID deciderUserId, String note) {
        if (!isPending()) {
            throw new IllegalStateException("ALREADY_DECIDED: this request is " + status);
        }
        this.status = approve ? APPROVED : DECLINED;
        this.decidedByUserId = deciderUserId;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
        this.updatedAt = Instant.now();
    }

    /**
     * Withdrawn by the stylist, or revoked by the salon.
     *
     * <p>Allowed from pending AND from approved: plans change, and a stylist who no longer needs
     * Tuesday off should be able to give the slot back rather than leave the salon short. The
     * service deletes the matching availability rows when this happens.
     */
    public void cancel() {
        if (CANCELLED.equals(status) || DECLINED.equals(status)) {
            throw new IllegalStateException("NOT_CANCELLABLE: this request is already " + status);
        }
        this.status = CANCELLED;
        this.updatedAt = Instant.now();
    }
}
