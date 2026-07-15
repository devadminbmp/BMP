package com.bmp.payment.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.payment_order.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "payment_order", schema = "payment_schema")
public class PaymentOrder {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "razorpay_order_id", length = 64)
    private String razorpayOrderId;
    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "amount_paise", nullable = false)
    private Money amountPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "commission_paise", nullable = false)
    private Money commissionPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "salon_share_paise", nullable = false)
    private Money salonSharePaise;
    @Column(name = "razorpay_raw_webhook", columnDefinition = "jsonb")
    private String razorpayRawWebhook;
    @Column(name = "payment_captured_at")
    private Instant paymentCapturedAt;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentOrder() {} // JPA

    public PaymentOrder(UUID bookingId, String razorpayOrderId, String idempotencyKey, Money amountPaise, Money commissionPaise, Money salonSharePaise, String razorpayRawWebhook, Instant paymentCapturedAt, String status) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.razorpayOrderId = razorpayOrderId;
        this.idempotencyKey = idempotencyKey;
        this.amountPaise = amountPaise;
        this.commissionPaise = commissionPaise;
        this.salonSharePaise = salonSharePaise;
        this.razorpayRawWebhook = razorpayRawWebhook;
        this.paymentCapturedAt = paymentCapturedAt;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getRazorpayOrderId() { return razorpayOrderId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Money getAmountPaise() { return amountPaise; }
    public Money getCommissionPaise() { return commissionPaise; }
    public Money getSalonSharePaise() { return salonSharePaise; }
    public String getRazorpayRawWebhook() { return razorpayRawWebhook; }
    public Instant getPaymentCapturedAt() { return paymentCapturedAt; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    // Mutators used by service layer
    public void setStatus(String status) {
        this.status = status;
    }

    public void setPaymentCapturedAt(Instant paymentCapturedAt) {
        this.paymentCapturedAt = paymentCapturedAt;
    }
}
