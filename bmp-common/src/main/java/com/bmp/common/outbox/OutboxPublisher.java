package com.bmp.common.outbox;

import com.bmp.common.events.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The ONLY way a module emits a cross-module event.
 *
 * <p>Usage inside a module's @Transactional service method:
 * <pre>
 *   bookingRepository.save(booking);
 *   outbox.publish(new BookingCompleted(booking.getId(), ...));
 *   // both rows commit atomically, or neither does
 * </pre>
 */
@Component
public class OutboxPublisher {

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.objectMapper = objectMapper;
    }

    public void publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "OutboxPublisher.publish must be called inside an active transaction — " +
                "the whole point is atomic commit with the business change. Event: " + event.eventType());
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            em.persist(new OutboxEntry(event.eventType(), event.aggregateId(), json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event not serializable: " + event.eventType(), e);
        }
    }
}
