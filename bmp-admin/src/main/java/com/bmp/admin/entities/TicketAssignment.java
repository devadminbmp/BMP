package com.bmp.admin.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement of a ticket between people. Append-only. Session 64 (V013).
 *
 * <h2>Why this is separate from {@code ticket_escalation}</h2>
 * Escalation records a move UP the tier ladder — "was this handled at the right level". This
 * records every move, including sideways and back to the pool — "who is working what right now".
 *
 * <p>They look similar enough to merge and must not be: every workload query would have to filter
 * escalation rows out, and every escalation audit would have to filter reassignments out. Two
 * questions, two tables, no filtering either way.
 *
 * <h2>Why there are no setters at all</h2>
 * A trail that can be edited is not a trail. The database revokes UPDATE and DELETE on this table
 * (V013), and the absence of setters here means the application cannot even try. Both, deliberately:
 * the grant is the guarantee, the missing setters are what makes a mistake fail at compile time
 * rather than at runtime in front of an auditor.
 */
@Entity
@Table(name = "ticket_assignment", schema = "admin_schema")
@Getter
public class TicketAssignment {

    /** Ticket moved from one person to another. */
    public static final String REASSIGN = "reassign";
    /** An agent took an unassigned ticket for themselves. */
    public static final String CLAIM = "claim";
    /** Somebody gave the ticket to a specific person. */
    public static final String ASSIGN = "assign";
    /** Handed to a different function — finance, ops — rather than a different person at the same job. */
    public static final String TRANSFER = "transfer";
    /** Put back in the pool. `to_staff_id` is null and that is a real state, not missing data. */
    public static final String RELEASE = "release";
    /** Moved up a tier. Also written to ticket_escalation; this keeps the workload view complete. */
    public static final String ESCALATE = "escalate";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "from_staff_id")
    private UUID fromStaffId;

    /** Null on a release. See RELEASE. */
    @Column(name = "to_staff_id")
    private UUID toStaffId;

    /**
     * Who performed the move — NOT the same as {@link #fromStaffId}.
     *
     * An ops admin reassigning somebody else's ticket is the common case, and collapsing the two
     * would lose the one fact worth having when asking why a ticket left an agent's queue.
     */
    @Column(name = "actor_staff_id", nullable = false)
    private UUID actorStaffId;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketAssignment() {
        // JPA
    }

    public TicketAssignment(UUID ticketId, UUID fromStaffId, UUID toStaffId, UUID actorStaffId,
                            String action, String reason) {
        this.id = UUID.randomUUID();
        this.ticketId = ticketId;
        this.fromStaffId = fromStaffId;
        this.toStaffId = toStaffId;
        this.actorStaffId = actorStaffId;
        this.action = action;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
