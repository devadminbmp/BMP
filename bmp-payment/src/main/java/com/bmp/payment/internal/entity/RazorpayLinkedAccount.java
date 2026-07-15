package com.bmp.payment.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.razorpay_linked_account.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "razorpay_linked_account", schema = "payment_schema")
public class RazorpayLinkedAccount {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "razorpay_account_id", length = 64)
    private String razorpayAccountId;
    @Column(name = "verification_status", nullable = false, length = 20)
    private String verificationStatus;
    @Column(name = "bookings_blocked", nullable = false)
    private boolean bookingsBlocked;
    @Column(name = "bank_account_changed_at")
    private Instant bankAccountChangedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RazorpayLinkedAccount() {} // JPA

    public RazorpayLinkedAccount(UUID salonId, String razorpayAccountId, String verificationStatus, boolean bookingsBlocked, Instant bankAccountChangedAt) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.razorpayAccountId = razorpayAccountId;
        this.verificationStatus = verificationStatus;
        this.bookingsBlocked = bookingsBlocked;
        this.bankAccountChangedAt = bankAccountChangedAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getRazorpayAccountId() { return razorpayAccountId; }
    public String getVerificationStatus() { return verificationStatus; }
    public boolean isBookingsBlocked() { return bookingsBlocked; }
    public Instant getBankAccountChangedAt() { return bankAccountChangedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
