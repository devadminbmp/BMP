package com.bmp.booking.entities;

import com.bmp.booking.api.BookingStatus;
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
 * JPA entity for booking_schema.booking.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 * Status changes only via BookingStatus.assertTransition — see BookingService.
 */
@Entity
@Table(name = "booking", schema = "booking_schema")
@Getter
public class Booking {

    @Id
    private UUID id;

    @Column(name = "booking_ref", nullable = false, length = 20)
    private String bookingRef;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;
    @Setter
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "final_amount_paise", nullable = false)
    private Money finalAmountPaise;
    @Setter
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "total_refunded_paise", nullable = false)
    private Money totalRefundedPaise;
    @Setter
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "commission_paise", nullable = false)
    private Money commissionPaise;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot", nullable = false, columnDefinition = "jsonb")
    private String policySnapshot;
    @Setter
    @Column(name = "refund_window_open", nullable = false)
    private boolean refundWindowOpen;
    @Setter
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    // ---- V005: the coupon applied at checkout ----------------------------------------------
    //
    // Frozen here, not re-derived from the coupon. If a coupon is later paused, edited, or its
    // percentage changed, this booking must still show what the customer actually agreed to.
    // Same principle as policySnapshot above.

    @Column(name = "coupon_id")
    private UUID couponId;

    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "discount_paise", nullable = false)
    private Money discountPaise;

    /**
     * The basket BEFORE the discount.
     *
     * <p>Stored rather than computed as final + discount, because finalAmountPaise will later be
     * touched by refunds and adjustments — at which point that arithmetic stops working and the
     * original agreement becomes unrecoverable.
     */
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "gross_amount_paise", nullable = false)
    private Money grossAmountPaise;

    /** pre_discount = the salon absorbs the discount; post_discount = BMP does. */
    @Column(name = "commission_base", nullable = false, length = 15)
    private String commissionBase;

    // ---- V006: who to tell, and who is standing in front of you ----------------------------
    //
    // A snapshot of the customer's contact details and the salon's name, taken once at booking
    // time. bmp-booking previously held nothing but a customer_id, which meant two things were
    // impossible: telling the customer anything, and telling the salon who was arriving.
    //
    // NOT a general copy of the user record — four fields, for delivery and for saying a name
    // out loud. See V006's header for why these are snapshotted rather than looked up live
    // (short version: cancelling must not fail because bmp-user is restarting).
    //
    // ALL NULLABLE. Every booking created before V006 has nulls, and a booking created while
    // bmp-user was unreachable has them too — because the rule is that the booking still
    // succeeds in that case. Consumers must handle null; see NotificationDispatcher, which
    // logs loudly rather than pretending a message was sent.

    /** As held by bmp-user at booking time. Null for pre-V006 bookings. */
    @Setter
    @Column(name = "customer_name", length = 160)
    private String customerName;

    /**
     * Unmasked, on purpose — it has to work as an SMS destination.
     *
     * <p>Masking is a presentation decision and belongs at the edge that knows who is asking:
     * the customer sees their own number, the salon sees {@code 98765 4••••} until they take an
     * audited action. Storing it masked would make it useless for its primary job.
     */
    @Setter
    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    /** Optional twice over: nullable column, and bmp-user's email is itself optional. */
    @Setter
    @Column(name = "customer_email", length = 160)
    private String customerEmail;

    /**
     * The salon's name when the booking was made.
     *
     * <p>"Your booking is confirmed" is a message from nobody. Snapshotted for the same reason
     * as price and policy: a salon that rebrands must not retroactively rewrite what a customer
     * was told six months ago.
     */
    @Setter
    @Column(name = "salon_name_snapshot", length = 200)
    private String salonNameSnapshot;

    // ---- V007: rescheduling, and a cancellation fee that is actually decided ---------------

    /**
     * When this appointment was ORIGINALLY for. Written once at creation, never moved.
     *
     * <p>This is the anchor the cancellation clock runs against, and it exists because
     * rescheduling is otherwise a refund loophole: book Saturday 11:00, reschedule to next month
     * at 10:00 on the day, then cancel "with three weeks' notice" — free, having cost the salon
     * Saturday's slot with an hour's warning.
     *
     * <p>Null for pre-V007 bookings, with no backfill. The service falls back to the earliest
     * {@code service_start}, which for a booking that has never been rescheduled IS the original
     * start — correct for every existing row by construction, since rescheduling did not exist.
     */
    @Setter
    @Column(name = "original_start")
    private Instant originalStart;

    /**
     * CUSTOMER reschedules only.
     *
     * <p>A salon moving its own bookings must not eat the customer's allowance — they didn't ask
     * for that change and shouldn't be punished for it. {@code booking_modification.actor}
     * records which kind each move was.
     */
    @Setter
    @Column(name = "reschedule_count", nullable = false)
    private int rescheduleCount = 0;

    /**
     * The cancellation decision, WRITTEN at cancellation rather than computed on read.
     *
     * <p>It is what the customer was told: the app shows the fee before they confirm, and
     * storing the same number means a dispute is settled by reading a row rather than re-running
     * a calculation against a policy that may since have changed. Recomputing would also need
     * "now" to be a past value, and would drift every time it ran.
     */
    @Setter
    @Column(name = "cancellation_fee_bps", nullable = false)
    private int cancellationFeeBps = 0;

    /**
     * The rupee figure shown, in integer paise. Stored as well as the rate, because
     * {@code finalAmountPaise} can later be touched by refunds — at which point recomputing
     * bps × amount stops reproducing what anyone agreed to.
     */
    @Setter
    @Column(name = "cancellation_fee_paise", nullable = false)
    private long cancellationFeePaise = 0L;

    /**
     * free | late | no_notice | salon_cancelled | no_policy.
     *
     * <p>Not derivable from the bps: a salon whose late fee is 0 produces the same 0 as a free
     * cancellation, and "you cancelled in good time" reads very differently from "we don't
     * charge for late cancellations". Support reads this; it shouldn't have to infer intent
     * from a number.
     */
    @Setter
    @Column(name = "cancellation_fee_reason", length = 20)
    private String cancellationFeeReason;

    // ══ V009 (Session 52) — bookings taken at the counter or over the phone. ══════════════════

    /**
     * {@code online} = booked in the app by a BMP account. {@code counter} = taken by salon staff
     * for somebody with no account.
     *
     * <p>Not derivable from "customerId is null" for long — a counter customer who later signs up
     * gets linked, and would then have both — so it is stored rather than inferred. It decides who
     * gets notified, whether an online payment order is opened, and how the row is read back.
     */
    @Setter
    @Column(name = "source", nullable = false, length = 10)
    private String source = "online";

    /**
     * {@code salon_schema.salon_customer.id} — the SALON's own contact record (V026), NOT a BMP
     * user. Set for counter bookings, null for online ones; the CHECK in V009 makes that exclusive.
     *
     * <p>No foreign key: it points into another service's schema, and a cross-service FK turns one
     * service's migration into the other's outage.
     */
    @Setter
    @Column(name = "salon_customer_id")
    private UUID salonCustomerId;

    /** Which member of salon staff took it down. Useful when a quoted price is disputed. */
    @Setter
    @Column(name = "taken_by_staff_id")
    private UUID takenByStaffId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True when this was taken at the salon rather than booked through the app. */
    public boolean isCounterBooking() { return "counter".equals(source); }

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
        // Defaults matching V005's column defaults, so a booking created through this
        // pre-V005 constructor behaves exactly as it did before: no discount, gross == final.
        this.discountPaise = Money.ZERO;
        this.grossAmountPaise = finalAmountPaise;
        this.commissionBase = "post_discount";
    }

    public void touch() { this.updatedAt = Instant.now(); }

    /**
     * Record the coupon that was applied.
     *
     * <p>Called once, during creation, inside the same transaction that redeems it. A named
     * method rather than four setters because these four values only ever make sense together
     * — a discount with no gross, or a coupon id with no discount, is a corrupt booking.
     */
    public void applyDiscount(UUID couponId, Money gross, Money discount, Money finalAmount, String commissionBase) {
        this.couponId = couponId;
        this.grossAmountPaise = gross;
        this.discountPaise = discount;
        this.finalAmountPaise = finalAmount;
        this.commissionBase = commissionBase;
        touch();
    }

    // Getters for the V005 fields come from the class-level @Getter — declaring them here
    // would be a duplicate-method compile error.
}
