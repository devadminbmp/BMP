package com.bmp.admin.repositories;

import com.bmp.admin.entities.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
    List<SupportTicket> findByStatus(String status);
    List<SupportTicket> findByAssignedStaffId(UUID assignedStaffId);
    List<SupportTicket> findByStatusAndAssignedStaffId(String status, UUID assignedStaffId);
    long countByTicketRefStartingWith(String prefix);

    /**
     * Every ticket this person raised, newest first. Session 45.
     *
     * <p>Backed by idx_support_ticket_requester (V006). This is now the most frequent read on
     * the table — every owner and customer who opens Help runs it, which is far more often than
     * staff open the queue.
     */
    List<SupportTicket> findByRaisedByIdOrderByCreatedAtDesc(UUID raisedById);

    /**
     * Every ticket raised for a salon, newest first — including ones a MANAGER raised.
     *
     * <p>Support at a salon is a salon-level concern, not a personal one: an owner asking "did
     * anyone report the payout problem?" shouldn't have to poll each manager. Backed by the
     * partial index idx_support_ticket_salon (V006).
     */
    List<SupportTicket> findBySalonIdOrderByCreatedAtDesc(UUID salonId);

    // ---- Session 23: real numbers for the ops overview -------------------------------------

    /** Everything not resolved or closed. */
    long countByStatusNotIn(List<String> statuses);

    /**
     * Tickets that have breached their first-response SLA.
     *
     * <p>First response, not resolution — silence is what makes customers angry, far more than
     * a hard problem taking a while. This drives the red number on the console's overview, which
     * previously reported a hardcoded 0 (meaning "not measured", not "none").
     *
     * <p>Uses the partial index created in V003.
     */
    @Query("SELECT COUNT(t) FROM SupportTicket t " +
           "WHERE t.firstRespondedAt IS NULL " +
           "AND t.firstResponseDueAt IS NOT NULL " +
           "AND t.firstResponseDueAt < :now " +
           "AND t.status NOT IN ('resolved', 'closed')")
    long countBreachingSla(@Param("now") Instant now);

    /**
     * The next ticket number, from a sequence. V008 (Session 48).
     *
     * <p>Replaces {@code countByTicketRefStartingWith(prefix) + 1}, which handed two concurrent
     * tickets the same reference — one of them then died on the unique index, so a customer
     * raising a ticket got a 500. nextval() is atomic and cannot do that.
     *
     * <p>Returns the NUMBER only; the caller adds the 'TCK-<year>-' prefix. Keeping the year in
     * Java means the sequence never has to be reset, and a sequence that never resets cannot
     * collide. See V008's header for why per-year numbering was rejected.
     */
    @Query(value = "SELECT nextval('admin_schema.support_ticket_ref_seq')", nativeQuery = true)
    long nextTicketNumber();

    // ---- V009 (Session 57): a real queue, and the counter's repair path ---------------------

    /**
     * Open tickets held by one person. The truth behind {@code BmpStaff.openTicketCount}.
     *
     * <p>The counter is denormalised because assignment reads it on every incoming ticket; this
     * exists so drift is always recoverable. See {@code TicketAssignmentService.recount}.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(t) FROM SupportTicket t
            WHERE t.assignedStaffId = :staffId
              AND t.status NOT IN ('resolved', 'closed')
            """)
    long countOpenForAssignee(@org.springframework.data.repository.query.Param("staffId") java.util.UUID staffId);

    /**
     * The working queue, filtered and ordered IN THE DATABASE.
     *
     * <h2>Why this had to move out of Java</h2>
     * {@code SupportDeskController.list} loaded every ticket and filtered the list in memory. That
     * is correct at ten tickets and falls over at ten thousand — and a support queue is the one
     * screen that is busiest precisely when the platform is having its worst day.
     *
     * <p>Every parameter is nullable and means "don't filter on this", so one query serves the
     * whole console: my tickets, the unassigned pool, one tier, one status.
     *
     * <p>Ordered the way an agent should work: UNASSIGNED FIRST (nobody owns it, so nobody is
     * accountable yet), then oldest. Deliberately NOT by priority — priority is set by hand and
     * drifts upward until everything is urgent, whereas "nobody has answered this in six hours" is
     * a fact. Backed by {@code idx_ticket_queue}.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT t FROM SupportTicket t
            WHERE (:tier IS NULL OR t.tier = :tier)
              AND (:status IS NULL OR t.status = :status)
              AND (:assigneeId IS NULL OR t.assignedStaffId = :assigneeId)
              AND (:unassignedOnly = false OR t.assignedStaffId IS NULL)
            ORDER BY CASE WHEN t.assignedStaffId IS NULL THEN 0 ELSE 1 END ASC,
                     t.createdAt ASC
            """)
    org.springframework.data.domain.Page<SupportTicket> queue(
            @org.springframework.data.repository.query.Param("tier") Short tier,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("assigneeId") java.util.UUID assigneeId,
            @org.springframework.data.repository.query.Param("unassignedOnly") boolean unassignedOnly,
            org.springframework.data.domain.Pageable pageable);
}
