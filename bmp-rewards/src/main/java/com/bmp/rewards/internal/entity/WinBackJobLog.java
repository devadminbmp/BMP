package com.bmp.rewards.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.win_back_job_log.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "win_back_job_log", schema = "rewards_schema")
public class WinBackJobLog {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
    @Column(name = "reactivated", nullable = false)
    private boolean reactivated;

    protected WinBackJobLog() {} // JPA

    public WinBackJobLog(UUID userId, Instant sentAt, boolean reactivated) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.sentAt = sentAt;
        this.reactivated = reactivated;

    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getSentAt() { return sentAt; }
    public boolean isReactivated() { return reactivated; }
}
