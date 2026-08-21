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

    protected SalonReview() {} // JPA

    public SalonReview(UUID salonId) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.status = "pending";
        this.submittedAt = Instant.now();
        this.checks = "{}";
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
