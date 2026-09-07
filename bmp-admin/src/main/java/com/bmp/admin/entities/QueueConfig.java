package com.bmp.admin.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * How tickets get assigned at one tier. V011, Session 59.
 *
 * <p>Darshan: <i>"who will assign the tickets can be a business analyst, or automatically, or
 * manual also."</i> All three, and it is CONFIGURATION rather than three code paths — they share
 * the same queue and the same assignment service, differing only in whether and by whom an assignee
 * is chosen.
 *
 * <p>Per TIER rather than global, because the right answer differs by volume: L1 is high-volume and
 * suits auto, while an ops queue is low-volume and high-stakes and often wants a person deciding
 * who takes what.
 */
@Entity
@Table(name = "queue_config", schema = "admin_schema")
@Getter
public class QueueConfig {

    /** Least-loaded person at the tier, chosen on arrival. The Session 57 behaviour. */
    public static final String AUTO = "auto";
    /** Nobody is assigned; the pool is worked by whoever picks something up. */
    public static final String MANUAL = "manual";
    /** Everything lands on one named person, who distributes it. */
    public static final String ANALYST = "analyst";

    @Id
    private UUID id;

    @Column(name = "tier", nullable = false)
    private short tier;

    @Setter
    @Column(name = "assignment_mode", nullable = false, length = 20)
    private String assignmentMode = AUTO;

    /** Required when the mode is {@code analyst}; meaningless otherwise, hence nullable. */
    @Setter
    @Column(name = "analyst_staff_id")
    private UUID analystStaffId;

    /**
     * Stop handing work to somebody already drowning. 0 = no cap.
     *
     * <p>Auto-assignment without a cap will give the least-loaded person their fortieth ticket when
     * everyone is at forty — which looks like fair distribution and is a desk that has stopped
     * coping. A cap pushes the overflow into the visible unassigned pool instead.
     */
    @Setter
    @Column(name = "max_open_per_agent", nullable = false)
    private int maxOpenPerAgent;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Setter
    @Column(name = "updated_by_staff_id")
    private UUID updatedByStaffId;

    protected QueueConfig() {} // JPA

    public boolean isAuto() { return AUTO.equals(assignmentMode); }
    public boolean isAnalyst() { return ANALYST.equals(assignmentMode); }
    public boolean isManual() { return MANUAL.equals(assignmentMode); }
}
