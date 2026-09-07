package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A salon awaiting (or having received) a human decision — admin_schema.salon_review, V003.
 *
 * <p>A salon that signs up is NOT visible to customers until somebody confirms it's a real
 * business at a real address. Without this gate, "list your salon" is an open door to putting
 * anything in front of your customers under your brand.
 *
 * <p>{@code checks} records WHAT the reviewer verified, not just that they clicked approve. Six
 * months later, "who approved this and what did they actually look at" has an answer.
 */
@Entity
@Table(name = "salon_review", schema = "admin_schema")
public class SalonReview {

    @Id
    private UUID id;

    /** Logical ref -> salon_schema.salon. Cross-schema FKs aren't used in this repo. */
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;

    /** pending | approved | rejected | suspended */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    /** Shown to the owner on rejection. The service requires it for that transition. */
    @Column(name = "decision_note")
    private String decisionNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checks", columnDefinition = "jsonb")
    private String checks;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Which attempt this row is. V007 (Session 46).
     *
     * <p>A salon now has ONE ROW PER SUBMISSION rather than one row forever, so a rejection is
     * never overwritten by a retry. See the migration header.
     */
    @Column(name = "submission_count", nullable = false)
    private int submissionCount;

    /** What the owner says they fixed. Null on a first submission. */
    @Column(name = "resubmission_note")
    private String resubmissionNote;

    protected SalonReview() {} // JPA

    /** A salon's first submission. */
    public SalonReview(UUID salonId) {
        this(salonId, 1, null);
    }

    /**
     * A submission, first or later.
     *
     * @param submissionCount 1 for a new salon; previous + 1 for a resubmission after rejection.
     * @param resubmissionNote what the owner changed. Null for a first submission.
     */
    public SalonReview(UUID salonId, int submissionCount, String resubmissionNote) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.status = "pending";
        this.submittedAt = Instant.now();
        this.checks = "{}";
        this.submissionCount = submissionCount;
        this.resubmissionNote = resubmissionNote;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** One-way decision recording — deliberately not a set of free-form setters. */
    public void decide(String status, UUID decidedBy, String note, String checksJson) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decisionNote = note;
        this.checks = checksJson == null ? "{}" : checksJson;
        this.decidedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public int getSubmissionCount() { return submissionCount; }
    public String getResubmissionNote() { return resubmissionNote; }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getStatus() { return status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDecisionNote() { return decisionNote; }
    public String getChecks() { return checks; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
