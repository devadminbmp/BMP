package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A refund request (admin_schema.refund_request, V005).
 *
 * <p>This is the DECISION, not the payout. Support agrees a customer is owed money; bmp-payment
 * — which doesn't exist yet — will one day return it. Separating the two means the agreement is
 * recorded and auditable whether or not the money has moved, and the day payments arrive there's
 * a queue of approved requests waiting rather than a spreadsheet.
 *
 * <p>{@code blocked} is the honest state for "agreed, but there is no way to pay it yet". It is
 * not a rejection — the customer's claim stands — and keeping them distinct means nobody later
 * mistakes a technical limitation for a refusal.
 */
@Entity
@Table(name = "refund_request", schema = "admin_schema")
public class RefundRequest {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "booking_ref", length = 20)
    private String bookingRef;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "salon_id")
    private UUID salonId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "booking_amount_paise", nullable = false)
    private long bookingAmountPaise;

    @Column(name = "reason", nullable = false)
    private String reason;

    /** requested | approved | rejected | paid | blocked */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "blocked_reason")
    private String blockedReason;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_by_email", length = 160)
    private String requestedByEmail;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_by_email", length = 160)
    private String decidedByEmail;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_reference", length = 120)
    private String paymentReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefundRequest() {} // JPA

    public RefundRequest(UUID bookingId, String bookingRef, UUID customerUserId, UUID salonId,
                         long amountPaise, long bookingAmountPaise, String reason,
                         UUID requestedBy, String requestedByEmail,
                         String status, String blockedReason) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.bookingRef = bookingRef;
        this.customerUserId = customerUserId;
        this.salonId = salonId;
        this.amountPaise = amountPaise;
        this.bookingAmountPaise = bookingAmountPaise;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedByEmail = requestedByEmail;
        this.status = status;
        this.blockedReason = blockedReason;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void decide(String status, UUID by, String byEmail, String note, String blockedReason) {
        this.status = status;
        this.decidedBy = by;
        this.decidedByEmail = byEmail;
        this.decisionNote = note;
        this.blockedReason = blockedReason;
        this.decidedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getBookingRef() { return bookingRef; }
    public UUID getCustomerUserId() { return customerUserId; }
    public UUID getSalonId() { return salonId; }
    public long getAmountPaise() { return amountPaise; }
    public long getBookingAmountPaise() { return bookingAmountPaise; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public String getBlockedReason() { return blockedReason; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getRequestedByEmail() { return requestedByEmail; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDecidedByEmail() { return decidedByEmail; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getPaidAt() { return paidAt; }
    public String getPaymentReference() { return paymentReference; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
