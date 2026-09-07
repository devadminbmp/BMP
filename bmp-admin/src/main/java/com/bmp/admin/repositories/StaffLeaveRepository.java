package com.bmp.admin.repositories;

import com.bmp.admin.entities.StaffLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Staff time off. Session 59. */
public interface StaffLeaveRepository extends JpaRepository<StaffLeave, UUID> {

    /** One person's history, newest first. */
    List<StaffLeave> findByStaffIdOrderByStartsOnDesc(UUID staffId);

    /** The approver's inbox. Backed by idx_leave_pending. */
    List<StaffLeave> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * Who is off on a given day. The query the team calendar and the assignment engine both run.
     *
     * <p>Backed by {@code idx_leave_window}, which is partial on {@code status = 'approved'} —
     * a pending request is not absence, and counting it would take somebody out of the rotation
     * before anybody agreed they could go.
     */
    @Query("""
            SELECT l FROM StaffLeave l
            WHERE l.status = 'approved'
              AND l.startsOn <= :date
              AND l.endsOn >= :date
            """)
    List<StaffLeave> approvedOn(@Param("date") LocalDate date);

    /**
     * Overlapping leave for one person, whatever its state except cancelled/rejected.
     *
     * <p>Used to refuse a duplicate request. Somebody asking twice for the same week is usually a
     * double-tap, and two approved rows for one absence make the leave balance wrong in a way
     * nobody notices until the year-end count.
     */
    @Query("""
            SELECT l FROM StaffLeave l
            WHERE l.staffId = :staffId
              AND l.status IN ('pending', 'approved')
              AND l.startsOn <= :endsOn
              AND l.endsOn >= :startsOn
            """)
    List<StaffLeave> overlapping(@Param("staffId") UUID staffId,
                                  @Param("startsOn") LocalDate startsOn,
                                  @Param("endsOn") LocalDate endsOn);

    /**
     * Approved leave in a window, for counting days taken.
     *
     * <p>Returns the ROWS and lets the service add up the days, rather than doing date arithmetic
     * in JPQL. Subtracting two dates is not portable JPQL — it works on one dialect and silently
     * fails on the next — and a leave BALANCE that is wrong is worse than one that is slow. The
     * result set is a person's leave for a year: tens of rows.
     */
    @Query("""
            SELECT l FROM StaffLeave l
            WHERE l.staffId = :staffId AND l.status = 'approved'
              AND l.startsOn >= :from AND l.endsOn <= :to
            """)
    List<StaffLeave> approvedBetween(@Param("staffId") UUID staffId,
                                      @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Approved leave OVERLAPPING a window — for one person. Session 65.
     *
     * <h2>Why this exists alongside {@link #approvedBetween}</h2>
     * {@code approvedBetween} requires the leave to sit ENTIRELY inside the window
     * ({@code startsOn >= from AND endsOn <= to}). For a rough count that is fine. For a leave
     * BALANCE it is a hole: somebody off from 30 March to 2 April falls entirely inside neither
     * financial year, so their four days are charged to nobody and vanish from both years' totals.
     *
     * <p>Nobody would notice — the number is simply smaller than it should be, in the employee's
     * favour, once a year. This overlaps instead, and {@code StaffLeave.daysChargedWithin} charges
     * only the portion of the leave that falls inside the window, so the two years add up to four.
     *
     * <p>{@code approvedBetween} is kept as-is: it is still what "leave that happened during this
     * month" means, and changing it under its existing caller to fix a different problem is how a
     * report quietly starts answering a different question.
     */
    @Query("""
            SELECT l FROM StaffLeave l
            WHERE l.staffId = :staffId AND l.status = 'approved'
              AND l.startsOn <= :to AND l.endsOn >= :from
            """)
    List<StaffLeave> approvedOverlapping(@Param("staffId") UUID staffId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * The same, for EVERYBODY. The main admin's HR overview. Session 65.
     *
     * <p>One query rather than one per employee. The HR screen shows every staff member against
     * every leave type; done per-person that is N queries for N people plus N more for their
     * entitlements, which is the N+1 that turns a 12-person team into a slow page and a 60-person
     * team into a broken one. Grouped by staff in Java, where grouping is free.
     */
    @Query("""
            SELECT l FROM StaffLeave l
            WHERE l.status = 'approved'
              AND l.startsOn <= :to AND l.endsOn >= :from
            ORDER BY l.startsOn DESC
            """)
    List<StaffLeave> approvedOverlappingAll(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
