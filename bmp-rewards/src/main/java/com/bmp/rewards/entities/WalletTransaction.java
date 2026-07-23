package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.wallet_transaction.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "wallet_transaction", schema = "rewards_schema")
public class WalletTransaction {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;
    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "amount_paise", nullable = false)
    private Money amountPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "balance_after_paise", nullable = false)
    private Money balanceAfterPaise;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletTransaction() {} // JPA

    public WalletTransaction(UUID walletId, String transactionType, Money amountPaise, Money balanceAfterPaise) {
        this.id = UuidV7.generate();
        this.walletId = walletId;
        this.transactionType = transactionType;
        this.amountPaise = amountPaise;
        this.balanceAfterPaise = balanceAfterPaise;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getWalletId() { return walletId; }
    public String getTransactionType() { return transactionType; }
    public Money getAmountPaise() { return amountPaise; }
    public Money getBalanceAfterPaise() { return balanceAfterPaise; }
    public Instant getCreatedAt() { return createdAt; }
}
