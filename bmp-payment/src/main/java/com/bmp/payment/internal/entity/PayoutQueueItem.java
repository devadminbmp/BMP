package com.bmp.payment.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.payout_queue_item.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "payout_queue_item", schema = "payment_schema")
public class PayoutQueueItem {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "amount_paise", nullable = false)
    private Money amountPaise;
    @Column(name = "payout_eligible_after", nullable = false)
    private Instant payoutEligibleAfter;
    @Column(name = "queue_status", nullable = false, length = 20)
    private String queueStatus;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PayoutQueueItem() {} // JPA

    public PayoutQueueItem(UUID bookingId, UUID salonId, Money amountPaise, Instant payoutEligibleAfter, String queueStatus) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.salonId = salonId;
        this.amountPaise = amountPaise;
        this.payoutEligibleAfter = payoutEligibleAfter;
        this.queueStatus = queueStatus;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getSalonId() { return salonId; }
    public Money getAmountPaise() { return amountPaise; }
    public Instant getPayoutEligibleAfter() { return payoutEligibleAfter; }
    public String getQueueStatus() { return queueStatus; }
    public Instant getCreatedAt() { return createdAt; }
}
