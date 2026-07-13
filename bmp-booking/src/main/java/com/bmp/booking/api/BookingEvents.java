package com.bmp.booking.api;

import com.bmp.common.events.DomainEvent;
import com.bmp.common.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Events this module emits. These ARE the old Kafka topics, unchanged in shape.
 * Consumers today: payment (queue payout), review (enable review),
 * rewards (referral check), notification (reminders, prompts).
 */
public final class BookingEvents {

    private BookingEvents() {}

    public record BookingCreated(
            UUID bookingId, UUID customerId, UUID salonId, UUID stylistId,
            Instant scheduledStart, Money finalAmount
    ) implements DomainEvent {
        @Override public String eventType()   { return "booking.created"; }
        @Override public UUID aggregateId()   { return bookingId; }
    }

    public record BookingCompleted(
            UUID bookingId, UUID customerId, UUID salonId, UUID stylistId,
            Money finalAmount, Money commissionAmount, Money salonNetAmount
    ) implements DomainEvent {
        @Override public String eventType()   { return "booking.completed"; }
        @Override public UUID aggregateId()   { return bookingId; }
    }

    public record BookingCancelled(
            UUID bookingId, UUID customerId, UUID salonId,
            Money cancellationFee, Money refundDue, String reason
    ) implements DomainEvent {
        @Override public String eventType()   { return "booking.cancelled"; }
        @Override public UUID aggregateId()   { return bookingId; }
    }
}
