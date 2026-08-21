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
}
