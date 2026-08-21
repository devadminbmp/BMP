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
