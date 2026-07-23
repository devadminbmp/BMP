package com.bmp.payment.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.payout_batch.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "payout_batch", schema = "payment_schema")
public class PayoutBatch {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "total_amount_paise", nullable = false)
    private Money totalAmountPaise;
    @Column(name = "razorpay_settlement_id", length = 64)
    private String razorpaySettlementId;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PayoutBatch() {} // JPA

    public PayoutBatch(UUID salonId, Money totalAmountPaise, String razorpaySettlementId, int retryCount, String status) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.totalAmountPaise = totalAmountPaise;
        this.razorpaySettlementId = razorpaySettlementId;
        this.retryCount = retryCount;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public Money getTotalAmountPaise() { return totalAmountPaise; }
    public String getRazorpaySettlementId() { return razorpaySettlementId; }
    public int getRetryCount() { return retryCount; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
