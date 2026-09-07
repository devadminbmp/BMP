package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One handover, up the ladder. V009, Session 57.
 *
 * <h2>What this is NOT: a new ticket</h2>
 * Darshan: <i>"if support can't resolve it, it should be passed to ops admins — he must get the
 * same and previous chat history, he can continue."</i>
 *
 * <p>That requirement is satisfied by what this class deliberately does not do. Messages belong to
 * the TICKET, and escalation does not create a new one — it raises the tier of the existing ticket
 * and records this row beside it. So the receiving person opens the same thread, with every
 * message, every photo and every earlier handover already in it.
 *
 * <p>The tempting alternative — a fresh ticket linked to the old — is how a customer ends up
 * repeating their problem to the third person they speak to, which is the most complained-about
 * thing in support anywhere. It also splits the SLA clock, so the new ticket looks fast while the
 * customer has been waiting since yesterday.
 *
 * <h2>Append-only</h2>
 * A ticket escalated twice has two rows. Nothing here is ever updated or deleted: this is the
 * record of who could not resolve something and who they asked, which is exactly the sort of thing
 * that gets quietly tidied when it becomes inconvenient.
 */
@Entity
@Table(name = "ticket_escalation", schema = "admin_schema")
@Getter
public class TicketEscalation {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "from_tier", nullable = false)
    private short fromTier;

    /** Always greater than {@code fromTier} — enforced by chk_escalation_upward in V009. */
    @Column(name = "to_tier", nullable = false)
    private short toTier;

    @Column(name = "from_staff_id", nullable = false)
    private UUID fromStaffId;

    /**
     * Null when it went into an unclaimed pool at the higher tier — a real state at 2am, and more
     * honest than assigning it to somebody who is asleep.
     */
    @Column(name = "to_staff_id")
    private UUID toStaffId;

    /**
     * Why. REQUIRED, minimum ten characters (chk_escalation_reason).
     *
     * <p>"Escalated" with no reason forces the receiving person to read the whole thread and guess
     * what was already tried — which defeats the point of carrying the history up with it.
     */
    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketEscalation() {} // JPA

    public TicketEscalation(UUID ticketId, short fromTier, short toTier,
                             UUID fromStaffId, UUID toStaffId, String reason) {
        this.id = UuidV7.generate();
        this.ticketId = ticketId;
        this.fromTier = fromTier;
        this.toTier = toTier;
        this.fromStaffId = fromStaffId;
        this.toStaffId = toStaffId;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
