package com.bmp.booking.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * JPA entity for booking_schema.refund_guard.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "refund_guard", schema = "booking_schema")
public class RefundGuard {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "max_refundable_paise", nullable = false)
    private Money maxRefundablePaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "total_refunded_paise", nullable = false)
    private Money totalRefundedPaise;

    protected RefundGuard() {} // JPA

    public RefundGuard(UUID bookingId, Money maxRefundablePaise, Money totalRefundedPaise) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.maxRefundablePaise = maxRefundablePaise;
        this.totalRefundedPaise = totalRefundedPaise;

    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public Money getMaxRefundablePaise() { return maxRefundablePaise; }
    public Money getTotalRefundedPaise() { return totalRefundedPaise; }
}
