package com.bmp.booking.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * The customer's bill for one booking. V008 (Session 49).
 *
 * <h2>One document, two states</h2>
 * Raised when a booking is confirmed and reads <b>amount due</b>. When the money is recorded the
 * same row becomes a <b>receipt</b> — same id, same number, now carrying how and when it was paid.
 *
 * <p>Not two documents, because they are the same commercial statement at two points in its life.
 * A customer quoting BMP-INV-000412 to support and finding a receipt numbered differently has to
 * be talked through it by a human.
 *
 * <h2>Everything here is frozen at issue</h2>
 * The salon's name, the customer's name, the amounts and the line items are all COPIED, not
 * joined. A salon renaming a service must not rewrite last month's invoices, and a refund must
 * not change what the document said when it was issued. {@code booking_service_item} already
 * snapshots names and prices for the same reason; this is that rule one level up.
 *
 * <p>Consequently there are almost no setters. The only things that legitimately change after
 * issue are the payment state and refunds, and each has a method that enforces its own rules.
 */
@Entity
@Table(name = "invoice", schema = "booking_schema")
@Getter
public class Invoice {

    public static final String DUE = "due";
    public static final String PAID = "paid";
    public static final String REFUNDED = "refunded";
    public static final String CANCELLED = "cancelled";

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true, updatable = false)
    private UUID bookingId;
    @Column(name = "salon_id", nullable = false, updatable = false)
    private UUID salonId;
    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    /**
     * BMP-INV-000412. Allocated from a Postgres sequence, never {@code count(*)+1}.
     *
     * <p>Session 48 fixed exactly that bug on support-ticket references: two documents raised in
     * the same second collide, and a deleted row causes a number to be reused. On an invoice that
     * is not an inconvenience, it is two customers holding the same bill number.
     */
    @Column(name = "invoice_no", nullable = false, unique = true, updatable = false, length = 24)
    private String invoiceNo;

    @Column(name = "salon_name", nullable = false, updatable = false, length = 160)
    private String salonName;
    @Column(name = "salon_address", updatable = false, length = 500)
    private String salonAddress;
    @Column(name = "customer_name", updatable = false, length = 160)
    private String customerName;
    @Column(name = "booking_ref", updatable = false, length = 32)
    private String bookingRef;

    @Column(name = "gross_paise", nullable = false, updatable = false)
    private long grossPaise;
    @Column(name = "discount_paise", nullable = false, updatable = false)
    private long discountPaise;
    @Column(name = "total_paise", nullable = false, updatable = false)
    private long totalPaise;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "paid_at")
    private Instant paidAt;
    @Column(name = "paid_method", length = 20)
    private String paidMethod;
    @Column(name = "paid_reference", length = 120)
    private String paidReference;
    /** Who said it was paid. The first question when the till doesn't balance. */
    @Column(name = "recorded_by_user_id")
    private UUID recordedByUserId;

    @Column(name = "refunded_paise", nullable = false)
    private long refundedPaise;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Invoice() {} // JPA

    public Invoice(UUID bookingId, UUID salonId, UUID customerId, String invoiceNo,
                    String salonName, String salonAddress, String customerName, String bookingRef,
                    long grossPaise, long discountPaise) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.salonId = salonId;
        this.customerId = customerId;
        this.invoiceNo = invoiceNo;
        this.salonName = salonName;
        this.salonAddress = salonAddress;
        this.customerName = customerName;
        this.bookingRef = bookingRef;
        this.grossPaise = grossPaise;
        this.discountPaise = discountPaise;
        // Computed here, once, so the document cannot disagree with itself. The DB CHECK asserts
        // the same identity — belt and braces on the one number the customer actually pays.
        this.totalPaise = grossPaise - discountPaise;
        this.status = DUE;
        this.refundedPaise = 0;
        this.issuedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isPaid() { return PAID.equals(status) || REFUNDED.equals(status); }

    /** What's still owed. Zero once paid; the un-refunded remainder after a partial refund. */
    public long outstandingPaise() {
        return isPaid() ? 0 : totalPaise;
    }

    /**
     * Record that the money arrived. This is what turns the invoice into a receipt.
     *
     * <h2>Idempotent, not an error on repeat</h2>
     * The payment webhook may deliver twice and a staff member may double-tap. Marking an
     * already-paid invoice paid again is a no-op rather than a 409, because the caller's intent
     * ("this is paid") is already true — and because throwing would make a retried webhook look
     * like a failure and get retried again.
     *
     * <p>The FIRST payment's details are kept. Overwriting them with the second delivery's
     * timestamp would quietly move when the money was received.
     *
     * @param method    'cash', 'upi', 'card', 'razorpay' — free text on purpose; how a Bengaluru
     *                  salon takes money is not something to enumerate today
     * @param recordedBy the staff member for a counter payment, null for a gateway callback
     */
    public void markPaid(String method, String reference, UUID recordedBy) {
        if (isPaid()) return;
        if (CANCELLED.equals(status)) {
            throw new IllegalStateException("INVOICE_CANCELLED: this booking was cancelled — "
                    + "recording a payment against it would create money with nothing to buy.");
        }
        this.status = PAID;
        this.paidAt = Instant.now();
        this.paidMethod = method;
        this.paidReference = reference;
        this.recordedByUserId = recordedBy;
        this.updatedAt = Instant.now();
    }

    /**
     * Money given back. Cumulative — two partial refunds add up.
     *
     * <p>The status only becomes {@code refunded} when the whole total has gone back. A partial
     * refund leaves it {@code paid}, because the customer did pay and the document should still
     * read as a receipt for what they were charged.
     */
    public void recordRefund(long paise) {
        if (paise <= 0) throw new IllegalArgumentException("REFUND_MUST_BE_POSITIVE");
        if (!isPaid()) {
            throw new IllegalStateException("NOT_PAID: nothing was received, so there is nothing "
                    + "to refund. Cancel the invoice instead.");
        }
        long next = this.refundedPaise + paise;
        if (next > totalPaise) {
            throw new IllegalStateException("REFUND_EXCEEDS_TOTAL: refunding " + paise
                    + " would take the total refunded to " + next + " against a bill of " + totalPaise);
        }
        this.refundedPaise = next;
        if (next == totalPaise) this.status = REFUNDED;
        this.updatedAt = Instant.now();
    }

    /**
     * The booking was cancelled before any money moved.
     *
     * <p>The row is KEPT, and the number is not reused. An invoice number that vanishes is a gap
     * somebody has to explain to an accountant; a cancelled invoice explains itself.
     */
    public void cancel() {
        if (isPaid()) {
            throw new IllegalStateException("ALREADY_PAID: refund it rather than cancelling — "
                    + "the money was received and the record has to show that.");
        }
        this.status = CANCELLED;
        this.updatedAt = Instant.now();
    }
}
