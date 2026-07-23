package com.bmp.booking.entities;

import com.bmp.booking.api.BookingStatus;
import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for booking_schema.booking.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "booking", schema = "booking_schema")
public class Booking {

    @Id
    private UUID id;

    @Column(name = "booking_ref", nullable = false, length = 20)
    private String bookingRef;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "final_amount_paise", nullable = false)
    private Money finalAmountPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "total_refunded_paise", nullable = false)
    private Money totalRefundedPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "commission_paise", nullable = false)
    private Money commissionPaise;
    @Column(name = "policy_snapshot", nullable = false, columnDefinition = "jsonb")
    private String policySnapshot;
    @Column(name = "refund_window_open", nullable = false)
    private boolean refundWindowOpen;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Booking() {} // JPA

    public Booking(String bookingRef, UUID salonId, UUID customerId, BookingStatus status, Money finalAmountPaise, Money totalRefundedPaise, Money commissionPaise, String policySnapshot, boolean refundWindowOpen, Instant confirmedAt) {
        this.id = UuidV7.generate();
        this.bookingRef = bookingRef;
        this.salonId = salonId;
        this.customerId = customerId;
        this.status = status;
        this.finalAmountPaise = finalAmountPaise;
        this.totalRefundedPaise = totalRefundedPaise;
        this.commissionPaise = commissionPaise;
        this.policySnapshot = policySnapshot;
        this.refundWindowOpen = refundWindowOpen;
        this.confirmedAt = confirmedAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getBookingRef() { return bookingRef; }
    public UUID getSalonId() { return salonId; }
    public UUID getCustomerId() { return customerId; }
    public BookingStatus getStatus() { return status; }
    public Money getFinalAmountPaise() { return finalAmountPaise; }
    public Money getTotalRefundedPaise() { return totalRefundedPaise; }
    public Money getCommissionPaise() { return commissionPaise; }
    public String getPolicySnapshot() { return policySnapshot; }
    public boolean isRefundWindowOpen() { return refundWindowOpen; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
