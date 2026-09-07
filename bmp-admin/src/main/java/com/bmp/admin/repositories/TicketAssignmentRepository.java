package com.bmp.admin.repositories;

import com.bmp.admin.entities.TicketAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * The ticket movement trail. Session 64 (V013).
 *
 * <p>Read-only in practice apart from {@code save} — the table revokes UPDATE and DELETE, and
 * {@link TicketAssignment} has no setters, so there is nothing here to change a row with.
 */
public interface TicketAssignmentRepository extends JpaRepository<TicketAssignment, UUID> {

    /** The history of one ticket, newest first — what the detail panel shows. */
    List<TicketAssignment> findByTicketIdOrderByCreatedAtDesc(UUID ticketId);

    /** Everything one agent has been handed, newest first. Used by the workload view. */
    List<TicketAssignment> findByToStaffIdOrderByCreatedAtDesc(UUID toStaffId);
}
