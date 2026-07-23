package com.bmp.common.kafka;

import com.bmp.common.outbox.OutboxEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Session 6: replaces the in-process {@code OutboxProcessor} drain loop that used to poll
 * {@code common_schema.outbox} and invoke consumers directly (see OutboxEntry/DomainEvent
 * javadocs for that earlier "LOCKED DECISION replacing Kafka" — explicitly reversed here at
 * Darshan's request; see CONTEXT.md Session 6 log). The transactional-write half of the
 * outbox pattern is unchanged and still required (OutboxPublisher still writes inside the
 * same DB transaction as the business change — that's what prevents a dual-write bug); only
 * the RELAY side changed, from "poll + call consumer in-process" to "poll + publish to
 * Kafka", so any number of independent consumers (not just bmp-notification) can subscribe
 * without bmp-common needing to know who they are.
 *
 * <p>Only one service should run this relay per environment — enable it with
 * {@code bmp.outbox.relay.enabled=true} in that service's application.yml (bmp-notification,
 * as of Session 6, matching where the old OutboxProcessor used to live). Running it in more
 * than one service is not incorrect (FOR UPDATE SKIP LOCKED prevents double-relay) but is
 * wasted polling against a table every other service also needs.
 */
@Component
@ConditionalOnProperty(name = "bmp.outbox.relay.enabled", havingValue = "true")
public class OutboxKafkaRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxKafkaRelay.class);
    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 8;

    private final EntityManager em;
    private final KafkaTemplate<String, String> kafka;

    public OutboxKafkaRelay(EntityManager em, KafkaTemplate<String, String> kafka) {
        this.em = em;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEntry> batch = em.createQuery("""
                    select o from OutboxEntry o
                    where o.processed = false and o.attempts < :max
                    order by o.createdAt
                    """, OutboxEntry.class)
                .setParameter("max", MAX_ATTEMPTS)
                .setMaxResults(BATCH)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", "-2") // SKIP LOCKED
                .getResultList();

        for (OutboxEntry entry : batch) {
            try {
                var message = MessageBuilder.withPayload(entry.getPayload())
                        .setHeader(KafkaHeaders.TOPIC, KafkaTopics.EVENTS)
                        .setHeader(KafkaHeaders.KEY, entry.getEventType())
                        .setHeader("eventType", entry.getEventType())
                        .setHeader("aggregateId", entry.getAggregateId().toString())
                        .build();
                kafka.send(message).get(); // synchronous within the poll loop — simplicity over throughput for now
                entry.markProcessed();
            } catch (Exception e) {
                entry.markFailed(e.getMessage());
                log.warn("outbox->kafka relay failed for {} [{}] attempt {}: {}",
                        entry.getEventType(), entry.getId(), entry.getAttempts(), e.getMessage());
            }
        }
    }
}
