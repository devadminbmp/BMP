package com.bmp.admin.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One cell of the authority matrix: this role, this action, this ceiling. V010, Session 58.
 *
 * <p>Read-mostly configuration. It is DATA rather than code so that adding a gated action, or
 * moving a limit, is an audited row change instead of a deploy — see {@code AuthorityService} for
 * why the whole thing is one mechanism rather than one ladder per feature.
 */
@Entity
@Table(name = "authority_limit", schema = "admin_schema")
@Getter
public class AuthorityLimit {

    @Id
    private UUID id;

    /** Stable code — {@code coupon.issue}, {@code refund.issue}. Never a display string. */
    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    /**
     * NULL = no ceiling. 0 = may request but never perform. n = may perform up to n.
     *
     * <p>The 0/NULL distinction is load-bearing: "cannot approve" and "not configured" must not
     * read the same, because the safe interpretation of the second is not the useful behaviour of
     * the first.
     */
    @Setter
    @Column(name = "max_value_paise")
    private Long maxValuePaise;

    /** Who to ask above this ceiling. NULL = top of the path; refuse rather than queue. */
    @Setter
    @Column(name = "approver_role", length = 30)
    private String approverRole;

    @Column(name = "step_order", nullable = false)
    private short stepOrder;

    /** Support goodwill must hang off a real complaint; an ops policy call need not. */
    @Column(name = "requires_ticket", nullable = false)
    private boolean requiresTicket;

    @Setter
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Setter
    @Column(name = "updated_by_staff_id")
    private UUID updatedByStaffId;

    protected AuthorityLimit() {} // JPA
}
