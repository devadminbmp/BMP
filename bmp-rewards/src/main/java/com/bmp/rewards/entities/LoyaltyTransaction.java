package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.loyalty_transaction.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "loyalty_transaction", schema = "rewards_schema")
public class LoyaltyTransaction {

    @Id
    private UUID id;

    @Column(name = "loyalty_account_id", nullable = false)
    private UUID loyaltyAccountId;
    @Column(name = "points_delta", nullable = false)
    private int pointsDelta;
    @Column(name = "reason", nullable = false, length = 60)
    private String reason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoyaltyTransaction() {} // JPA

    public LoyaltyTransaction(UUID loyaltyAccountId, int pointsDelta, String reason) {
        this.id = UuidV7.generate();
        this.loyaltyAccountId = loyaltyAccountId;
        this.pointsDelta = pointsDelta;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getLoyaltyAccountId() { return loyaltyAccountId; }
    public int getPointsDelta() { return pointsDelta; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
