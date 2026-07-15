package com.bmp.payment.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.bmp_account.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "bmp_account", schema = "payment_schema")
public class BmpAccount {

    @Id
    private UUID id;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "balance_paise", nullable = false)
    private Money balancePaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "total_refunds_absorbed_paise", nullable = false)
    private Money totalRefundsAbsorbedPaise;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BmpAccount() {} // JPA

    public BmpAccount(String accountType, Money balancePaise, Money totalRefundsAbsorbedPaise) {
        this.id = UuidV7.generate();
        this.accountType = accountType;
        this.balancePaise = balancePaise;
        this.totalRefundsAbsorbedPaise = totalRefundsAbsorbedPaise;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAccountType() { return accountType; }
    public Money getBalancePaise() { return balancePaise; }
    public Money getTotalRefundsAbsorbedPaise() { return totalRefundsAbsorbedPaise; }
    public Instant getUpdatedAt() { return updatedAt; }
}
