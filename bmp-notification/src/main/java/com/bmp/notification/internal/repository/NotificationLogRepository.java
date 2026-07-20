package com.bmp.notification.internal.repository;

import com.bmp.notification.internal.entity.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for NotificationLog entity (V009).
 * Per CONTEXT.md Architecture Rule: Modules NEVER import from another module's internal package.
 * This is internal — use only within bmp-notification service.
 */
@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    /**
     * Find notification by provider message ID (for deduplication & tracing)
     */
    Optional<NotificationLog> findByProviderMessageId(String providerMessageId);

    /**
     * Find notifications by outbox entry ID (tracing back to triggering event)
     */
    List<NotificationLog> findByOutboxEntryId(UUID outboxEntryId);

    /**
     * Find notifications for a specific recipient
     */
    Page<NotificationLog> findByRecipientId(UUID recipientId, Pageable pageable);

    /**
     * Find notifications by channel and status (for retry logic)
     */
    Page<NotificationLog> findByChannelAndStatus(
            NotificationLog.NotificationChannel channel,
            NotificationLog.NotificationStatus status,
            Pageable pageable
    );

    /**
     * Find failed notifications (for retry job)
     */
    List<NotificationLog> findByStatus(NotificationLog.NotificationStatus status);

    /**
     * Find notifications by template code (for analytics)
     */
    Page<NotificationLog> findByTemplateCode(String templateCode, Pageable pageable);

    /**
     * Count notifications by status
     */
    long countByStatus(NotificationLog.NotificationStatus status);

    /**
     * Find queued notifications older than a certain time (for timeout detection)
     */
    @Query("SELECT nl FROM NotificationLog nl WHERE nl.status = 'QUEUED' AND nl.createdAt < :threshold")
    List<NotificationLog> findStalledQueuedNotifications(@Param("threshold") Instant threshold);

    /**
     * Find notifications created within a time range (for bulk operations)
     */
    List<NotificationLog> findByCreatedAtBetween(Instant startTime, Instant endTime);

    /**
     * Find undelivered notifications for a recipient (including QUEUED and SENT)
     */
    @Query("SELECT nl FROM NotificationLog nl WHERE nl.recipientId = :recipientId AND nl.status IN ('QUEUED', 'SENT') ORDER BY nl.createdAt DESC")
    Page<NotificationLog> findPendingNotificationsForRecipient(
            @Param("recipientId") UUID recipientId,
            Pageable pageable
    );
}
