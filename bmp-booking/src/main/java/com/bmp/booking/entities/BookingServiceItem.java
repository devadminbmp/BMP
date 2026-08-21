package com.bmp.booking.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for booking_schema.booking_service_item.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "booking_service_item", schema = "booking_schema")
public class BookingServiceItem {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    @Column(name = "service_id", nullable = false)
    private UUID serviceId;
    @Column(name = "assigned_stylist_id")
    private UUID assignedStylistId;
    @Column(name = "selection_type", nullable = false, length = 20)
    private String selectionType;
    @Column(name = "service_start", nullable = false)
    private Instant serviceStart;
    @Column(name = "service_end", nullable = false)
    private Instant serviceEnd;
    @Column(name = "name_snapshot", nullable = false, length = 160)
    private String nameSnapshot;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "price_paise_snapshot", nullable = false)
    private Money pricePaiseSnapshot;
    @Column(name = "duration_shown_minutes", nullable = false)
    private int durationShownMinutes;
    @Column(name = "actual_duration_minutes", nullable = false)
    private int actualDurationMinutes;
    @Column(name = "item_status", nullable = false, length = 10)
    private String itemStatus;

    protected BookingServiceItem() {} // JPA

    public BookingServiceItem(UUID bookingId, UUID serviceId, UUID assignedStylistId, String selectionType, Instant serviceStart, Instant serviceEnd, String nameSnapshot, Money pricePaiseSnapshot, int durationShownMinutes, int actualDurationMinutes, String itemStatus) {
        this.id = UuidV7.generate();
        this.bookingId = bookingId;
        this.serviceId = serviceId;
        this.assignedStylistId = assignedStylistId;
        this.selectionType = selectionType;
        this.serviceStart = serviceStart;
        this.serviceEnd = serviceEnd;
        this.nameSnapshot = nameSnapshot;
        this.pricePaiseSnapshot = pricePaiseSnapshot;
        this.durationShownMinutes = durationShownMinutes;
        this.actualDurationMinutes = actualDurationMinutes;
        this.itemStatus = itemStatus;

    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getServiceId() { return serviceId; }
    public UUID getAssignedStylistId() { return assignedStylistId; }
    public String getSelectionType() { return selectionType; }
    public Instant getServiceStart() { return serviceStart; }
    public Instant getServiceEnd() { return serviceEnd; }
    public String getNameSnapshot() { return nameSnapshot; }
    public Money getPricePaiseSnapshot() { return pricePaiseSnapshot; }
    public int getDurationShownMinutes() { return durationShownMinutes; }
    public int getActualDurationMinutes() { return actualDurationMinutes; }
    public String getItemStatus() { return itemStatus; }

    /**
     * Session 16: item_status follows the parent booking's terminal state — set to
     * "completed" when the salon marks the booking done. A named mutator rather than a plain
     * setter because this is a one-way lifecycle step, not a free-form field (same spirit as
     * StaffInvites.setStatus and OutboxEntry.markProcessed).
     *
     * <p>Values in use: active | removed | completed (10-char column).
     */
    public void setItemStatus(String itemStatus) { this.itemStatus = itemStatus; }

    // ---- Session 37: rescheduling -----------------------------------------------------------
    //
    // The ONLY three fields a reschedule may touch. Price, duration and nameSnapshot stay
    // deliberately unsettable: the customer is moving an appointment, not rebuying it, and a
    // reschedule that re-derived the price would let a salon's price rise apply retroactively to
    // a booking somebody already agreed to.
    //
    // Setters rather than a single move() method because the stylist is resolved separately by
    // the availability algorithm — the three values don't arrive together, so an all-or-nothing
    // constructor would just be filled in twice.

    public void setServiceStart(Instant serviceStart) { this.serviceStart = serviceStart; }

    public void setServiceEnd(Instant serviceEnd) { this.serviceEnd = serviceEnd; }

    /** May change on a reschedule — the requested stylist can be busy at the new time. */
    public void setAssignedStylistId(UUID assignedStylistId) { this.assignedStylistId = assignedStylistId; }
}
