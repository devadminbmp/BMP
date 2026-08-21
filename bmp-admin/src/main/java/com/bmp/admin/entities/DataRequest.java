package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A data subject request under India's DPDP Act 2023 — admin_schema.data_request, V003.
 *
 * <p>People have the right to access and erase their personal data, on a statutory clock. This
 * row is how you PROVE you honoured a request: "we definitely did it" is not a defence without
 * a record, and the deadline is not advisory.
 *
 * <p>{@code identityVerifiedAt} gates fulfilment in the service layer. Honouring a forged
 * deletion request destroys a real customer's history; honouring a forged export hands their
 * life to a stranger. Both are breaches caused by helping too quickly — which is exactly the
 * failure mode of a well-meaning support team.
 */
@Entity
@Table(name = "data_request", schema = "admin_schema")
public class DataRequest {

    @Id
    private UUID id;

    /** export | delete | correct */
    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    /** Logical ref -> user_schema.users. */
    @Column(name = "subject_user_id", nullable = false)
    private UUID subjectUserId;

    @Column(name = "subject_email", length = 160)
    private String subjectEmail;

    /** received | verifying | in_progress | completed | rejected */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    /** The statutory deadline. Set at creation from a configurable number of days. */
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DataRequest() {} // JPA

    public DataRequest(String requestType, UUID subjectUserId, String subjectEmail, Instant dueAt) {
        this.id = UuidV7.generate();
        this.requestType = requestType;
        this.subjectUserId = subjectUserId;
        this.subjectEmail = subjectEmail;
        this.status = "received";
        this.dueAt = dueAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markIdentityVerified(UUID by, String note) {
        this.identityVerifiedAt = Instant.now();
        this.verifiedBy = by;
        this.status = "in_progress";
        if (note != null && !note.isBlank()) this.notes = note;
        this.updatedAt = Instant.now();
    }

    public void complete(UUID by, String note) {
        this.status = "completed";
        this.completedAt = Instant.now();
        this.completedBy = by;
        if (note != null && !note.isBlank()) this.notes = note;
        this.updatedAt = Instant.now();
    }

    public void reject(UUID by, String reason) {
        this.status = "rejected";
        this.completedBy = by;
        this.rejectionReason = reason;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isIdentityVerified() {
        return identityVerifiedAt != null;
    }

    public UUID getId() { return id; }
    public String getRequestType() { return requestType; }
    public UUID getSubjectUserId() { return subjectUserId; }
    public String getSubjectEmail() { return subjectEmail; }
    public String getStatus() { return status; }
    public Instant getIdentityVerifiedAt() { return identityVerifiedAt; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public Instant getDueAt() { return dueAt; }
    public Instant getCompletedAt() { return completedAt; }
    public UUID getCompletedBy() { return completedBy; }
    public String getRejectionReason() { return rejectionReason; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
