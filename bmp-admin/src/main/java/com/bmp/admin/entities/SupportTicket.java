package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for admin_schema.support_ticket.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "support_ticket", schema = "admin_schema")
@Getter
public class SupportTicket {

    @Id
    private UUID id;

    @Column(name = "ticket_ref", nullable = false, length = 20)
    private String ticketRef;
    @Column(name = "raised_by_type", nullable = false, length = 20)
    private String raisedByType;
    @Column(name = "raised_by_id", nullable = false)
    private UUID raisedById;
    @Column(name = "booking_id")
    private UUID bookingId;
    @Column(name = "category", nullable = false, length = 30)
    private String category;
    @Column(name = "subject", nullable = false, length = 200)
    private String subject;
    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Setter
    @Column(name = "priority", nullable = false, length = 10)
    private String priority;
    @Setter
    @Column(name = "assigned_staff_id")
    private UUID assignedStaffId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Setter
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // ---- V003: SLA tracking and requester contact -----------------------------------------

    /**
     * When a first reply is due.
     *
     * <p>This is the SLA that customers actually feel. Silence is what makes people angry —
     * far more than a hard problem taking a while — so the queue sorts on this, not on
     * resolution time.
     */
    @Setter
    @Column(name = "first_response_due_at")
    private Instant firstResponseDueAt;

    /** Set by the first CUSTOMER-VISIBLE reply. An internal note is not a response. */
    @Setter
    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;

    @Setter
    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    /**
     * Contact details for a requester with no account.
     *
     * <p>V002 assumed every ticket has a {@code raised_by_id}, but a walk-in complaint or an
     * email from someone who never finished signing up has none — and without these the agent
     * has no way to reply at all.
     */
    @Setter
    @Column(name = "requester_email", length = 160)
    private String requesterEmail;

    @Setter
    @Column(name = "requester_phone", length = 20)
    private String requesterPhone;

    @Setter
    @Column(name = "salon_id")
    private UUID salonId;

    /**
     * Who raised this, and which salon it is about — SNAPSHOTS taken at raise time. Session 64 (V013).
     *
     * Not joins. A ticket is a record of a conversation that happened: if the person later renames
     * themselves or exercises their right to erasure, this record must still say who it concerned
     * at the time. A live lookup would rewrite history and, for a deleted account, would blank the
     * requester out of an audit record we are required to keep.
     *
     * The queue also lists fifty of these at once — fifty cross-service lookups per page load would
     * each be able to fail, and a failed name is indistinguishable in the UI from no name at all.
     */
    @Setter
    @Column(name = "requester_name", length = 160)
    private String requesterName;

    @Setter
    @Column(name = "salon_name", length = 160)
    private String salonName;

    /**
     * Did TicketPriorityPolicy set the priority, or did a person?
     *
     * false means a human decided, and the policy must not overrule them on any later re-derivation.
     * Without this the only safe option would be to derive once at creation and never again — which
     * would leave a ticket that is later linked to a salon carrying a priority computed back when we
     * believed it was a consumer.
     */
    @Setter
    @Column(name = "priority_auto", nullable = false)
    private boolean priorityAuto = true;

    /**
     * WHICH FUNCTION owns this ticket — orthogonal to {@link #tier}, which is HOW SENIOR. Session 64 (V014).
     *
     * A refund dispute does not need a more senior SUPPORT person; it needs finance, who are
     * deliberately tier 0 and off the escalation ladder entirely. Escalating three times to reach
     * the platform owner so they can forward it by hand is not a workflow, so desk and tier move
     * independently. A tier-2 ticket at the finance desk is a normal, coherent state.
     *
     * Stored on the ticket rather than inferred from the assignee's role, because a ticket can be
     * AT the finance desk before anybody there has picked it up — and an unassigned transferred
     * ticket is exactly the one most likely to be lost if it looks identical to an unassigned new one.
     */
    @Setter
    @Column(name = "handling_desk", nullable = false, length = 20)
    private String handlingDesk = "support";

    /**
     * Why it landed on this desk. Denormalised from the ticket_assignment trail on purpose: the
     * receiving agent needs it BEFORE they read anything else, and should not have to open a
     * separate history to find out why this is theirs.
     */
    @Setter
    @Column(name = "desk_transfer_reason")
    private String deskTransferReason;

    @Setter
    @Column(name = "desk_changed_at")
    private Instant deskChangedAt;

    // ══ V009 (Session 57) — the ladder, the clock and the read marks ══════════════════════════

    /**
     * The tier that currently owns this ticket. Raised by escalation; never lowered automatically.
     *
     * <p>Assignment picks somebody at EXACTLY this tier — not "this or above". An ops admin should
     * not be handed routine first-line work because they were idle, and a ticket escalated to ops
     * must never drop back to an agent who cannot action it.
     */
    @Setter
    @Column(name = "tier", nullable = false)
    private short tier = 1;

    @Setter
    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "escalation_count", nullable = false)
    private int escalationCount;

    /** The two numbers a support desk is actually judged on — see V009 on why they are stored. */
    @Setter
    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Setter
    @Column(name = "last_customer_message_at")
    private Instant lastCustomerMessageAt;

    @Setter
    @Column(name = "last_staff_message_at")
    private Instant lastStaffMessageAt;

    /** Per SIDE, not per person — a ticket has one customer and one current owner. */
    @Setter
    @Column(name = "customer_last_read_at")
    private Instant customerLastReadAt;

    @Setter
    @Column(name = "staff_last_read_at")
    private Instant staffLastReadAt;

    public short getTier() { return tier; }
    public Instant getEscalatedAt() { return escalatedAt; }
    public int getEscalationCount() { return escalationCount; }
    public Instant getFirstResponseAt() { return firstResponseAt; }
    public Instant getLastCustomerMessageAt() { return lastCustomerMessageAt; }
    public Instant getLastStaffMessageAt() { return lastStaffMessageAt; }
    public Instant getCustomerLastReadAt() { return customerLastReadAt; }
    public Instant getStaffLastReadAt() { return staffLastReadAt; }

    /**
     * Move this ticket one rung up. The ticket is the SAME ticket — see TicketEscalationService
     * for why escalation must never create a new one.
     */
    public void escalateTo(short newTier) {
        this.tier = newTier;
        this.escalatedAt = Instant.now();
        this.escalationCount = this.escalationCount + 1;
    }

    /** True when the customer has said something the current owner has not answered. */
    public boolean awaitingStaffReply() {
        return lastCustomerMessageAt != null
                && (lastStaffMessageAt == null || lastStaffMessageAt.isBefore(lastCustomerMessageAt));
    }

    protected SupportTicket() {} // JPA

    public SupportTicket(String ticketRef, String raisedByType, UUID raisedById, UUID bookingId, String category, String subject, String status, String priority, UUID assignedStaffId, Instant resolvedAt) {
        this.id = UuidV7.generate();
        this.ticketRef = ticketRef;
        this.raisedByType = raisedByType;
        this.raisedById = raisedById;
        this.bookingId = bookingId;
        this.category = category;
        this.subject = subject;
        this.status = status;
        this.priority = priority;
        this.assignedStaffId = assignedStaffId;
        this.resolvedAt = resolvedAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }
}
