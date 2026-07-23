package com.bmp.payment.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.commission_ledger.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "commission_ledger", schema = "payment_schema")
public class CommissionLedger {

    @Id
    private UUID id;

    @Column(name = "entry_type", nullable = false, length = 30)
    private String entryType;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "amount_paise", nullable = false)
    private Money amountPaise;
    @Column(name = "reference_id")
    private UUID referenceId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CommissionLedger() {} // JPA

    public CommissionLedger(String entryType, Money amountPaise, UUID referenceId) {
        this.id = UuidV7.generate();
        this.entryType = entryType;
        this.amountPaise = amountPaise;
        this.referenceId = referenceId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEntryType() { return entryType; }
    public Money getAmountPaise() { return amountPaise; }
    public UUID getReferenceId() { return referenceId; }
    public Instant getCreatedAt() { return createdAt; }
}
