package com.bmp.payment.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.saas_invoice.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "saas_invoice", schema = "payment_schema")
public class SaasInvoice {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "amount_paise", nullable = false)
    private Money amountPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "gst_paise", nullable = false)
    private Money gstPaise;
    @Column(name = "razorpay_payment_link_id", length = 64)
    private String razorpayPaymentLinkId;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SaasInvoice() {} // JPA

    public SaasInvoice(UUID subscriptionId, Money amountPaise, Money gstPaise, String razorpayPaymentLinkId, String status) {
        this.id = UuidV7.generate();
        this.subscriptionId = subscriptionId;
        this.amountPaise = amountPaise;
        this.gstPaise = gstPaise;
        this.razorpayPaymentLinkId = razorpayPaymentLinkId;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public Money getAmountPaise() { return amountPaise; }
    public Money getGstPaise() { return gstPaise; }
    public String getRazorpayPaymentLinkId() { return razorpayPaymentLinkId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
