package com.bmp.admin.repositories;

import com.bmp.admin.entities.TicketEscalation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * The handover trail. Session 57.
 *
 * <p>Read-mostly and append-only — see {@link TicketEscalation} for why nothing here is ever
 * updated or deleted. Backed by {@code idx_escalation_ticket}.
 */
public interface TicketEscalationRepository extends JpaRepository<TicketEscalation, UUID> {

    /** Oldest first, so the trail reads as a story: raised → tried → handed up → handed up again. */
    List<TicketEscalation> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    /** How many times this ticket has been handed up. Shown as a badge on a busy queue. */
    long countByTicketId(UUID ticketId);
}
