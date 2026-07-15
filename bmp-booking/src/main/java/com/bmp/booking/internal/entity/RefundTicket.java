package com.bmp.booking.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for booking_schema.refund_ticket.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "refund_ticket", schema = "booking_schema")
public class RefundTicket {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "raised_by_role", nullable = false, length = 20)
    private String raisedByRole;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "amount_paise", nullable = false)
    private Money amountPaise;
    @Column(name = "commission_absorbed_by", nullable = false, length = 10)
    private String commissionAbsorbedBy;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefundTicket() {} // JPA

    public RefundTicket(UUID bookingId, String raisedByRole, Money amountPaise, String commissionAbsorbedBy, String status) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.raisedByRole = raisedByRole;
        this.amountPaise = amountPaise;
        this.commissionAbsorbedBy = commissionAbsorbedBy;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getRaisedByRole() { return raisedByRole; }
    public Money getAmountPaise() { return amountPaise; }
    public String getCommissionAbsorbedBy() { return commissionAbsorbedBy; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
