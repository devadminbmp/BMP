package com.bmp.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker for cross-module domain events. LOCKED DECISION: modules communicate
 * asynchronously ONLY through events persisted to the outbox — never by calling
 * another module's internals, never by touching another module's tables.
 *
 * <p>These were the Kafka topics in the original design. They are now rows in
 * {@code common_schema.outbox} processed by {@code OutboxProcessor}. If a real
 * throughput number ever demands Kafka, the event types stay identical and only
 * the transport changes.
 *
 * <p>Naming convention mirrors the old topics: booking.created, payment.success,
 * booking.completed, booking.cancelled, review.created, stylist.moved,
 * referral.completed.
 */
public interface DomainEvent {

    /** Stable event name, e.g. "booking.completed". Used for consumer routing. */
    String eventType();

    /** Aggregate that emitted this event (booking id, payment id, ...). */
    UUID aggregateId();

    default Instant occurredAt() {
        return Instant.now();
    }
}
