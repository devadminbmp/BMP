package com.bmp.admin.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for admin_schema.support_ticket.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "support_ticket", schema = "admin_schema")
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
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "priority", nullable = false, length = 10)
    private String priority;
    @Column(name = "assigned_staff_id")
    private UUID assignedStaffId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;

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

    public UUID getId() { return id; }
    public String getTicketRef() { return ticketRef; }
    public String getRaisedByType() { return raisedByType; }
    public UUID getRaisedById() { return raisedById; }
    public UUID getBookingId() { return bookingId; }
    public String getCategory() { return category; }
    public String getSubject() { return subject; }
    public String getStatus() { return status; }
    public String getPriority() { return priority; }
    public UUID getAssignedStaffId() { return assignedStaffId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    // Mutators used by service layer
    public void setStatus(String status) {
        this.status = status;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public void setAssignedStaffId(UUID assignedStaffId) {
        this.assignedStaffId = assignedStaffId;
    }

    /**
     * Update the updatedAt timestamp from services that mutate the entity.
     * Services are responsible for calling touch() inside @Transactional methods.
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
