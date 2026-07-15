package com.bmp.rewards.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.loyalty_account.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "loyalty_account", schema = "rewards_schema")
public class LoyaltyAccount {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "points_balance", nullable = false)
    private int pointsBalance;
    @Column(name = "tier", nullable = false, length = 10)
    private String tier;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoyaltyAccount() {} // JPA

    public LoyaltyAccount(UUID userId, int pointsBalance, String tier) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.pointsBalance = pointsBalance;
        this.tier = tier;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public int getPointsBalance() { return pointsBalance; }
    public String getTier() { return tier; }
    public Instant getUpdatedAt() { return updatedAt; }
}
