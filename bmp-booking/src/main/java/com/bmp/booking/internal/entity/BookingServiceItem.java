package com.bmp.booking.internal.entity;

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
}
