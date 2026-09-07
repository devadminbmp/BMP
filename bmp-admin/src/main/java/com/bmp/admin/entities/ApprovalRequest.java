package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * "I want to do X and I'm not allowed to." V010, Session 58.
 *
 * <h2>Generic on purpose</h2>
 * One table for every gated action. {@code payload} is JSONB, so adding "waive a no-show fee" means
 * a row in {@code authority_limit} and an executor branch — never a migration here.
 *
 * <p>The trade-off is stated plainly because it is real: JSONB means the database cannot validate
 * what is inside. That is accepted, because the alternative — a column per field per action — makes
 * every new desk power a schema change, and schema changes are exactly what stop people adding the
 * row at all. Only the executor for a given {@code actionType} ever reads inside the payload.
 *
 * <h2>Approved and executed are different states</h2>
 * An approved coupon still has to be created, and that call can fail. Collapsing the two would
 * leave a request marked approved with nothing issued — and a customer promised something they
 * never received, which is worse than a refusal.
 */
@Entity
@Table(name = "approval_request", schema = "admin_schema")
@Getter
public class ApprovalRequest {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String CANCELLED = "cancelled";
    public static final String EXECUTED = "executed";
    public static final String FAILED = "failed";

    @Id
    private UUID id;

    /** APR-000042 — what one person quotes to another. */
    @Column(name = "request_ref", nullable = false, length = 24)
    private String requestRef;

    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** Lifted out of the payload so a queue sorts and filters without parsing JSON per row. */
    @Column(name = "value_paise", nullable = false)
    private long valuePaise;

    @Column(name = "requested_by_staff_id", nullable = false)
    private UUID requestedByStaffId;

    @Column(name = "requested_by_role", nullable = false, length = 30)
    private String requestedByRole;

    @Column(name = "requested_by_tier", nullable = false)
    private short requestedByTier;

    /** The complaint behind it. Required when the matrix says the requester needs one. */
    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "justification", nullable = false, columnDefinition = "text")
    private String justification;

    /** Moves UP the path on each decline-and-escalate, so the row always has an addressee. */
    @Setter
    @Column(name = "current_approver_role", nullable = false, length = 30)
    private String currentApproverRole;

    @Setter
    @Column(name = "current_step", nullable = false)
    private short currentStep;

    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status = PENDING;

    @Setter
    @Column(name = "decided_by_staff_id")
    private UUID decidedByStaffId;

    @Setter
    @Column(name = "decided_at")
    private Instant decidedAt;

    @Setter
    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @Setter
    @Column(name = "executed_at")
    private Instant executedAt;

    @Setter
    @Column(name = "execution_error", columnDefinition = "text")
    private String executionError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApprovalRequest() {} // JPA

    public ApprovalRequest(String requestRef, String actionType, String payload, long valuePaise,
                            UUID requestedByStaffId, String requestedByRole, short requestedByTier,
                            UUID ticketId, String justification, String approverRole, short step) {
        this.id = UuidV7.generate();
        this.requestRef = requestRef;
        this.actionType = actionType;
        this.payload = payload;
        this.valuePaise = valuePaise;
        this.requestedByStaffId = requestedByStaffId;
        this.requestedByRole = requestedByRole;
        this.requestedByTier = requestedByTier;
        this.ticketId = ticketId;
        this.justification = justification;
        this.currentApproverRole = approverRole;
        this.currentStep = step;
        this.status = PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public boolean isPending() { return PENDING.equals(status); }

    /** Approved but not yet carried out — the window in which execution can still fail. */
    public boolean awaitingExecution() { return APPROVED.equals(status); }

    public void decide(String newStatus, UUID staffId, String note) {
        this.status = newStatus;
        this.decidedByStaffId = staffId;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
        this.updatedAt = this.decidedAt;
    }

    /** Handed further up: still pending, now addressed to somebody else. */
    public void moveTo(String approverRole, short step) {
        this.currentApproverRole = approverRole;
        this.currentStep = step;
        this.updatedAt = Instant.now();
    }

    public void markExecuted() {
        this.status = EXECUTED;
        this.executedAt = Instant.now();
        this.executionError = null;
        this.updatedAt = this.executedAt;
    }

    /**
     * Approved, then the action itself failed.
     *
     * <p>Deliberately NOT reverted to pending: somebody DID approve it, and erasing that would lose
     * the decision. It shows in the queue as failed, with the error, to be retried or abandoned
     * knowingly.
     */
    public void markFailed(String error) {
        this.status = FAILED;
        this.executionError = error;
        this.updatedAt = Instant.now();
    }
}
