package com.bmp.booking.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for booking_schema.booking_disruption.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "booking_disruption", schema = "booking_schema")
public class BookingDisruption {

    @Id
    private UUID id;

    @Column(name = "booking_service_item_id", nullable = false)
    private UUID bookingServiceItemId;
    @Column(name = "notify_customer", nullable = false)
    private boolean notifyCustomer;
    @Column(name = "salon_deadline", nullable = false)
    private Instant salonDeadline;
    @Column(name = "rejection_count", nullable = false)
    private int rejectionCount;
    @Column(name = "customer_acceptance", nullable = false, length = 20)
    private String customerAcceptance;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BookingDisruption() {} // JPA

    public BookingDisruption(UUID bookingServiceItemId, boolean notifyCustomer, Instant salonDeadline, int rejectionCount, String customerAcceptance) {
        this.id = UuidV7.generate();
        this.bookingServiceItemId = bookingServiceItemId;
        this.notifyCustomer = notifyCustomer;
        this.salonDeadline = salonDeadline;
        this.rejectionCount = rejectionCount;
        this.customerAcceptance = customerAcceptance;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingServiceItemId() { return bookingServiceItemId; }
    public boolean isNotifyCustomer() { return notifyCustomer; }
    public Instant getSalonDeadline() { return salonDeadline; }
    public int getRejectionCount() { return rejectionCount; }
    public String getCustomerAcceptance() { return customerAcceptance; }
    public Instant getCreatedAt() { return createdAt; }
}
