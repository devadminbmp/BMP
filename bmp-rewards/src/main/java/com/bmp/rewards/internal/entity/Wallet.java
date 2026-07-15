package com.bmp.rewards.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.wallet.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "wallet", schema = "rewards_schema")
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "balance_paise", nullable = false)
    private Money balancePaise;
    @Column(name = "is_frozen", nullable = false)
    private boolean isFrozen;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {} // JPA

    public Wallet(UUID userId, Money balancePaise, boolean isFrozen) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.balancePaise = balancePaise;
        this.isFrozen = isFrozen;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Money getBalancePaise() { return balancePaise; }
    public boolean isFrozen() { return isFrozen; }
    public Instant getUpdatedAt() { return updatedAt; }
}
