package com.bmp.common.kafka;

/**
 * Single-topic design for Session 6: every relayed outbox row lands on {@code bmp.events},
 * with the event type carried in the Kafka message key (see OutboxKafkaRelay) so consumers
 * can filter cheaply without deserializing payloads they don't care about. Split into
 * per-domain topics later only if a real throughput/ownership reason shows up — starting
 * with one topic keeps local dev (one topic to create) and consumer wiring simple.
 */
public final class KafkaTopics {
    public static final String EVENTS = "bmp.events";

    private KafkaTopics() {}
}
