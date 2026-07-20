package com.bmp.notification.internal.service;

import com.bmp.common.ids.UuidV7;
import com.bmp.notification.internal.entity.NotificationLog;
import com.bmp.notification.internal.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for NotificationLog business operations.
 * Per CONTEXT.md: "OutboxProcessor (scheduled, SKIP LOCKED) reads outbox → Publishes domain event
 * to consuming module → Consuming module processes and marks outbox entry done"
 * 
 * This service handles:
 * - Creating notification log entries (from outbox events)
 * - Tracking send attempts and delivery status
 * - Retry logic for failed sends
 * - Analytics queries
 * 
 * Phase 1: CRUD only — Phase 3 will add real WhatsApp/FCM/SMS providers
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationLogService {

    private final NotificationLogRepository repository;

    /**
     * Create a new notification log entry (QUEUED status).
     * Called by OutboxProcessor when an outbox event triggers a send.
     */
    @Transactional
    public NotificationLog create(NotificationLogCreateRequest request) {
        NotificationLog log = NotificationLog.builder()
                .id(UuidV7.generate())
                .channel(request.getChannel())
                .templateCode(request.getTemplateCode())
                .recipientId(request.getRecipientId())
                .payload(request.getPayload())
                .status(NotificationLog.NotificationStatus.QUEUED)
                .outboxEntryId(request.getOutboxEntryId())
                .build();

        log = repository.save(log);
        log("NotificationLog created: id={}, channel={}, template={}, recipient={}",
                log.getId(), log.getChannel(), log.getTemplateCode(), log.getRecipientId());
        return log;
    }

    /**
     * Get notification log by ID.
     */
    @Transactional(readOnly = true)
    public Optional<NotificationLog> getById(UUID id) {
        return repository.findById(id);
    }

    /**
     * Get notifications for a recipient (paginated).
     */
    @Transactional(readOnly = true)
    public Page<NotificationLog> getByRecipient(UUID recipientId, Pageable pageable) {
        return repository.findByRecipientId(recipientId, pageable);
    }

    /**
     * Get pending notifications for a recipient.
     */
    @Transactional(readOnly = true)
    public Page<NotificationLog> getPendingNotifications(UUID recipientId, Pageable pageable) {
        return repository.findPendingNotificationsForRecipient(recipientId, pageable);
    }

    /**
     * Get notifications by template code (for analytics).
     */
    @Transactional(readOnly = true)
    public Page<NotificationLog> getByTemplate(String templateCode, Pageable pageable) {
        return repository.findByTemplateCode(templateCode, pageable);
    }

    /**
     * Get notifications by channel and status.
     */
    @Transactional(readOnly = true)
    public Page<NotificationLog> getByChannelAndStatus(
            NotificationLog.NotificationChannel channel,
            NotificationLog.NotificationStatus status,
            Pageable pageable) {
        return repository.findByChannelAndStatus(channel, status, pageable);
    }

    /**
     * Mark notification as sent (provider accepted it).
     */
    @Transactional
    public NotificationLog markSent(UUID id, String providerMessageId) {
        NotificationLog log = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("NotificationLog not found: " + id));

        log.setStatus(NotificationLog.NotificationStatus.SENT);
        log.setProviderMessageId(providerMessageId);
        log.setSentAt(Instant.now());
        log = repository.save(log);
        
        log("NotificationLog marked SENT: id={}, provider_id={}", id, providerMessageId);
        return log;
    }

    /**
     * Mark notification as delivered.
     */
    @Transactional
    public NotificationLog markDelivered(UUID id) {
        NotificationLog log = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("NotificationLog not found: " + id));

        log.setStatus(NotificationLog.NotificationStatus.DELIVERED);
        log.setDeliveredAt(Instant.now());
        log = repository.save(log);
        
        log("NotificationLog marked DELIVERED: id={}", id);
        return log;
    }

    /**
     * Mark notification as failed with error message.
     */
    @Transactional
    public NotificationLog markFailed(UUID id, String errorMessage) {
        NotificationLog log = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("NotificationLog not found: " + id));

        log.setStatus(NotificationLog.NotificationStatus.FAILED);
        log.setErrorMessage(errorMessage);
        log = repository.save(log);
        
        log("NotificationLog marked FAILED: id={}, error={}", id, errorMessage);
        return log;
    }

    /**
     * Find stalled notifications (queued too long) for retry.
     */
    @Transactional(readOnly = true)
    public List<NotificationLog> findStalledNotifications(int maxAgeMinutes) {
        Instant threshold = Instant.now().minusSeconds((long) maxAgeMinutes * 60);
        return repository.findStalledQueuedNotifications(threshold);
    }

    /**
     * Get statistics: count by status.
     */
    @Transactional(readOnly = true)
    public NotificationStats getStats() {
        return NotificationStats.builder()
                .queuedCount(repository.countByStatus(NotificationLog.NotificationStatus.QUEUED))
                .sentCount(repository.countByStatus(NotificationLog.NotificationStatus.SENT))
                .deliveredCount(repository.countByStatus(NotificationLog.NotificationStatus.DELIVERED))
                .failedCount(repository.countByStatus(NotificationLog.NotificationStatus.FAILED))
                .build();
    }

    private void log(String message, Object... args) {
        logger.info(message, args);
    }

    // DTO helper methods for consistency
    public static class NotificationLogCreateRequest {
        private NotificationLog.NotificationChannel channel;
        private String templateCode;
        private UUID recipientId;
        private String payload;
        private UUID outboxEntryId;

        public NotificationLogCreateRequest(NotificationLog.NotificationChannel channel,
                                           String templateCode, UUID recipientId,
                                           String payload, UUID outboxEntryId) {
            this.channel = channel;
            this.templateCode = templateCode;
            this.recipientId = recipientId;
            this.payload = payload;
            this.outboxEntryId = outboxEntryId;
        }

        public NotificationLog.NotificationChannel getChannel() { return channel; }
        public String getTemplateCode() { return templateCode; }
        public UUID getRecipientId() { return recipientId; }
        public String getPayload() { return payload; }
        public UUID getOutboxEntryId() { return outboxEntryId; }
    }

    // Statistics DTO
    @lombok.Data
    @lombok.Builder
    public static class NotificationStats {
        private long queuedCount;
        private long sentCount;
        private long deliveredCount;
        private long failedCount;

        public long getTotalCount() {
            return queuedCount + sentCount + deliveredCount + failedCount;
        }
    }
}
