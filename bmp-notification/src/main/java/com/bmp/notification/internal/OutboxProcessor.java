package com.bmp.notification.internal;

import com.bmp.common.outbox.OutboxEntry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The single moving part that replaced the Kafka cluster.
 *
 * <p>Polls unprocessed outbox rows every 2s, routes them to consumers registered
 * per event type, marks processed. FOR UPDATE SKIP LOCKED makes this safe to run
 * on multiple app instances later without double-dispatch.
 *
 * <p>At-least-once semantics: every consumer must be idempotent (e.g. the
 * payout-queue consumer checks booking_id before inserting).
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 8;

    private final EntityManager em;
    private final Map<String, List<OutboxConsumer>> consumersByType;

    public OutboxProcessor(EntityManager em, List<OutboxConsumer> consumers) {
        this.em = em;
        this.consumersByType = consumers.stream()
                .collect(java.util.stream.Collectors.groupingBy(OutboxConsumer::eventType));
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void drain() {
        List<OutboxEntry> batch = em.createQuery("""
                    select o from OutboxEntry o
                    where o.processed = false and o.attempts < :max
                    order by o.createdAt
                    """, OutboxEntry.class)
                .setParameter("max", MAX_ATTEMPTS)
                .setMaxResults(BATCH)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", "-2") // SKIP LOCKED
                .getResultList();

        for (OutboxEntry entry : batch) {
            try {
                for (OutboxConsumer c : consumersByType.getOrDefault(entry.getEventType(), List.of())) {
                    c.consume(entry);
                }
                entry.markProcessed();
            } catch (Exception e) {
                entry.markFailed(e.getMessage());
                log.warn("outbox event {} [{}] failed attempt {}: {}",
                        entry.getEventType(), entry.getId(), entry.getAttempts(), e.getMessage());
            }
        }
    }

    /** Implement one of these per (eventType, side-effect) pair. Must be idempotent. */
    public interface OutboxConsumer {
        String eventType();
        void consume(OutboxEntry entry) throws Exception;
    }
}
