package com.bmp.payment.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.refund_execution.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "refund_execution", schema = "payment_schema")
public class RefundExecution {

    @Id
    private UUID id;

    @Column(name = "refund_ticket_id", nullable = false)
    private UUID refundTicketId;
    @Column(name = "razorpay_refund_id", length = 64)
    private String razorpayRefundId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "amount_paise", nullable = false)
    private Money amountPaise;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefundExecution() {} // JPA

    public RefundExecution(UUID refundTicketId, String razorpayRefundId, Money amountPaise, String status) {
        this.id = UuidV7.generate();
        this.refundTicketId = refundTicketId;
        this.razorpayRefundId = razorpayRefundId;
        this.amountPaise = amountPaise;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRefundTicketId() { return refundTicketId; }
    public String getRazorpayRefundId() { return razorpayRefundId; }
    public Money getAmountPaise() { return amountPaise; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
