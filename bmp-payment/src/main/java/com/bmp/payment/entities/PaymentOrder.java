package com.bmp.payment.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.payment_order.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "payment_order", schema = "payment_schema")
@Getter
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
    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "razorpay_raw_webhook", columnDefinition = "jsonb")
    private String razorpayRawWebhook;
    @Setter
    @Column(name = "payment_captured_at")
    private Instant paymentCapturedAt;
    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ══ V004 (Session 50) ═════════════════════════════════════════════════════════════════════

    /** Whose {@code salonSharePaise} this is. Without it a payout needs a cross-service join. */
    @Column(name = "salon_id")
    private UUID salonId;

    /**
     * The RATE that produced {@link #commissionPaise}, frozen with it.
     *
     * <p>Before V004 this service used a hardcoded 1200 bps for every salon while
     * {@code salon_policy.commission_bps} held a per-partner rate an admin had negotiated — so
     * the platform could agree 8%, display 8%, and charge 12%. Recording the rate next to the
     * amount makes a past split explainable rather than merely recomputable against today's rate.
     */
    @Column(name = "commission_bps")
    private Integer commissionBps;

    /** {@code pay_XXXX} — the reference on the customer's bank statement. Set at capture. */
    @Setter
    @Column(name = "gateway_payment_id", length = 64)
    private String gatewayPaymentId;

    /** Verbatim from the gateway. "It didn't work" is the commonest support contact. */
    @Setter
    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    /** Set once, when the gateway order is opened. */
    public void attachGatewayOrder(String gatewayOrderId) {
        if (this.razorpayOrderId != null) {
            throw new IllegalStateException("GATEWAY_ORDER_ALREADY_SET: this order is already "
                    + this.razorpayOrderId + " — a second one would let a customer pay twice.");
        }
        this.razorpayOrderId = gatewayOrderId;
    }

    /**
     * The money arrived.
     *
     * <h2>Idempotent, because gateways deliver at least once</h2>
     * A redelivered webhook must not move {@code payment_captured_at} — that timestamp is the
     * legal record of when the transaction happened, and overwriting it with the retry's arrival
     * time quietly falsifies it. Returns false when this was a repeat, so the caller knows not to
     * write a second ledger entry or confirm the booking again.
     *
     * @return true if this call actually captured; false if it was already captured
     */
    public boolean capture(String gatewayPaymentId, Instant capturedAt) {
        if ("captured".equals(this.status)) return false;
        this.status = "captured";
        this.gatewayPaymentId = gatewayPaymentId;
        this.paymentCapturedAt = capturedAt;
        this.failureReason = null;
        return true;
    }

    /** The payment failed. Not terminal — the customer may try again on the same order. */
    public void fail(String reason) {
        if ("captured".equals(this.status)) {
            throw new IllegalStateException("ALREADY_CAPTURED: refusing to mark a captured "
                    + "payment as failed — the money is here.");
        }
        this.status = "failed";
        this.failureReason = reason;
    }

    public boolean isCaptured() { return "captured".equals(status); }

    protected PaymentOrder() {} // JPA

    /**
     * @param commissionBps the salon's OWN rate, from salon_policy — never a platform constant
     */
    public PaymentOrder(UUID bookingId, UUID salonId, Integer commissionBps,
                         String razorpayOrderId, String idempotencyKey, Money amountPaise,
                         Money commissionPaise, Money salonSharePaise, String razorpayRawWebhook,
                         Instant paymentCapturedAt, String status) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.salonId = salonId;
        this.commissionBps = commissionBps;
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
}
