package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.referral_code.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "referral_code", schema = "rewards_schema")
public class ReferralCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "code", nullable = false, length = 30)
    private String code;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReferralCode() {} // JPA

    public ReferralCode(UUID userId, String code) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.code = code;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCode() { return code; }
    public Instant getCreatedAt() { return createdAt; }
}
