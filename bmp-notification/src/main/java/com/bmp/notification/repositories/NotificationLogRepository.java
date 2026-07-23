package com.bmp.notification.repositories;

import com.bmp.notification.entities.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Query methods below (paginated recipient lookup, pending queue, per-status counts, stalled
 * detection) were ported from Shivam's Session 7 BMP-30 work — that version was written
 * against the pre-flatten package layout and against column names that don't match the
 * actual V002 migration (recipient_id/error_message instead of the real
 * recipient_user_id/error_reason), so it was rebuilt here rather than copied as-is.
 */
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    Page<NotificationLog> findByRecipientUserId(UUID recipientUserId, Pageable pageable);

    List<NotificationLog> findByOutboxEntryId(UUID outboxEntryId);

    long countByStatus(String status);

    @Query("SELECT n FROM NotificationLog n WHERE n.recipientUserId = :recipientUserId AND n.status IN ('queued', 'sent') ORDER BY n.createdAt DESC")
    Page<NotificationLog> findPendingForRecipient(@Param("recipientUserId") UUID recipientUserId, Pageable pageable);

    @Query("SELECT n FROM NotificationLog n WHERE n.status = 'queued' AND n.createdAt < :threshold")
    List<NotificationLog> findStalledQueued(@Param("threshold") Instant threshold);
}
