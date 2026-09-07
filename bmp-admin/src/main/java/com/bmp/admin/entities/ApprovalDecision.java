package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One person's verdict on one request. V010, Session 58.
 *
 * <p>Append-only. A request declined by finance and then approved by ops has two rows, and the
 * first is exactly the kind of record that gets tidied away later when it becomes inconvenient —
 * which is precisely when it matters most.
 */
@Entity
@Table(name = "approval_decision", schema = "admin_schema")
@Getter
public class ApprovalDecision {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    /** approved | rejected | escalated */
    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "decided_by_staff_id", nullable = false)
    private UUID decidedByStaffId;

    @Column(name = "decided_by_role", nullable = false, length = 30)
    private String decidedByRole;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApprovalDecision() {} // JPA

    public ApprovalDecision(UUID requestId, String decision, UUID staffId, String role, String note) {
        this.id = UuidV7.generate();
        this.requestId = requestId;
        this.decision = decision;
        this.decidedByStaffId = staffId;
        this.decidedByRole = role;
        this.note = note;
        this.createdAt = Instant.now();
    }
}
