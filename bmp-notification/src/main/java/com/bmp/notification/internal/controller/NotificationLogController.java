package com.bmp.notification.internal.controller;

import com.bmp.notification.internal.entity.NotificationLog;
import com.bmp.notification.internal.service.NotificationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for NotificationLog endpoints.
 * Phase 1: CRUD only — no actual WhatsApp/SMS/push sending yet (Phase 3).
 * 
 * Base path: /api/notifications (routed via api-gateway at port 8080)
 * Service port: 8089 (bmp-notification-service)
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationLogController {

    private final NotificationLogService service;

    /**
     * GET /api/notifications/{id}
     * Retrieve a single notification log entry by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<NotificationLogResponse> getNotification(@PathVariable UUID id) {
        return service.getById(id)
                .map(log -> {
                    NotificationLogController.log.info("Retrieved notification: {}", id);
                    return ResponseEntity.ok(mapToResponse(log));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /api/notifications
     * List all notifications for a recipient (paginated).
     * Query param: recipientId (required)
     */
    @GetMapping
    public ResponseEntity<Page<NotificationLogResponse>> listNotifications(
            @RequestParam(required = false) UUID recipientId,
            Pageable pageable) {
        
        if (recipientId == null) {
            log.warn("Listing notifications without recipientId filter");
            return ResponseEntity.badRequest().build();
        }

        Page<NotificationLog> page = service.getByRecipient(recipientId, pageable);
        Page<NotificationLogResponse> response = page.map(this::mapToResponse);
        log.info("Listed {} notifications for recipient: {}", response.getNumberOfElements(), recipientId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/notifications/pending
     * List pending notifications (QUEUED or SENT) for a recipient.
     * Query param: recipientId (required)
     */
    @GetMapping("/pending")
    public ResponseEntity<Page<NotificationLogResponse>> getPendingNotifications(
            @RequestParam UUID recipientId,
            Pageable pageable) {
        
        Page<NotificationLog> page = service.getPendingNotifications(recipientId, pageable);
        Page<NotificationLogResponse> response = page.map(this::mapToResponse);
        log.info("Retrieved {} pending notifications for recipient: {}", 
                response.getNumberOfElements(), recipientId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/notifications/stats
     * Get notification statistics (counts by status).
     */
    @GetMapping("/stats")
    public ResponseEntity<NotificationStatsResponse> getStats() {
        NotificationLogService.NotificationStats stats = service.getStats();
        log.info("Retrieved notification stats: queued={}, sent={}, delivered={}, failed={}",
                stats.getQueuedCount(), stats.getSentCount(), 
                stats.getDeliveredCount(), stats.getFailedCount());
        return ResponseEntity.ok(new NotificationStatsResponse(stats));
    }

    /**
     * POST /api/notifications (Internal only — called by OutboxProcessor)
     * Create a new notification log entry.
     * 
     * TODO Phase 3: Integrate with real MSG91/FCM providers to trigger actual sends
     */
    @PostMapping
    public ResponseEntity<NotificationLogResponse> createNotification(
            @RequestBody CreateNotificationRequest request) {
        
        try {
            NotificationLogService.NotificationLogCreateRequest serviceRequest =
                    new NotificationLogService.NotificationLogCreateRequest(
                            NotificationLog.NotificationChannel.valueOf(request.getChannel()),
                            request.getTemplateCode(),
                            request.getRecipientId(),
                            request.getPayload(),
                            request.getOutboxEntryId()
                    );
            
            NotificationLog created = service.create(serviceRequest);
            log.info("Created notification log: id={}, channel={}, template={}",
                    created.getId(), created.getChannel(), created.getTemplateCode());
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(mapToResponse(created));
        } catch (IllegalArgumentException e) {
            log.error("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * PUT /api/notifications/{id}/sent
     * Mark notification as sent (provider accepted it).
     * 
     * TODO Phase 3: Called after successful MSG91/FCM API call
     */
    @PutMapping("/{id}/sent")
    public ResponseEntity<NotificationLogResponse> markSent(
            @PathVariable UUID id,
            @RequestBody MarkSentRequest request) {
        
        try {
            NotificationLog updated = service.markSent(id, request.getProviderMessageId());
            log.info("Marked notification SENT: id={}, provider_id={}", id, request.getProviderMessageId());
            return ResponseEntity.ok(mapToResponse(updated));
        } catch (IllegalArgumentException e) {
            log.error("Notification not found: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * PUT /api/notifications/{id}/delivered
     * Mark notification as delivered.
     * 
     * TODO Phase 3: Called on delivery receipt from provider
     */
    @PutMapping("/{id}/delivered")
    public ResponseEntity<NotificationLogResponse> markDelivered(@PathVariable UUID id) {
        try {
            NotificationLog updated = service.markDelivered(id);
            log.info("Marked notification DELIVERED: id={}", id);
            return ResponseEntity.ok(mapToResponse(updated));
        } catch (IllegalArgumentException e) {
            log.error("Notification not found: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * PUT /api/notifications/{id}/failed
     * Mark notification as failed with error message.
     * 
     * TODO Phase 3: Called on send failure from provider
     */
    @PutMapping("/{id}/failed")
    public ResponseEntity<NotificationLogResponse> markFailed(
            @PathVariable UUID id,
            @RequestBody MarkFailedRequest request) {
        
        try {
            NotificationLog updated = service.markFailed(id, request.getErrorMessage());
            log.warn("Marked notification FAILED: id={}, error={}", id, request.getErrorMessage());
            return ResponseEntity.ok(mapToResponse(updated));
        } catch (IllegalArgumentException e) {
            log.error("Notification not found: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    // Helper methods

    private NotificationLogResponse mapToResponse(NotificationLog log) {
        return NotificationLogResponse.builder()
                .id(log.getId())
                .channel(log.getChannel().name())
                .templateCode(log.getTemplateCode())
                .recipientId(log.getRecipientId())
                .payload(log.getPayload())
                .status(log.getStatus().name())
                .providerMessageId(log.getProviderMessageId())
                .errorMessage(log.getErrorMessage())
                .sentAt(log.getSentAt())
                .deliveredAt(log.getDeliveredAt())
                .createdAt(log.getCreatedAt())
                .updatedAt(log.getUpdatedAt())
                .build();
    }

    // Request/Response DTOs

    @lombok.Data
    @lombok.Builder
    public static class NotificationLogResponse {
        private UUID id;
        private String channel;
        private String templateCode;
        private UUID recipientId;
        private String payload;
        private String status;
        private String providerMessageId;
        private String errorMessage;
        private java.time.Instant sentAt;
        private java.time.Instant deliveredAt;
        private java.time.Instant createdAt;
        private java.time.Instant updatedAt;
    }

    @lombok.Data
    public static class CreateNotificationRequest {
        private String channel;
        private String templateCode;
        private UUID recipientId;
        private String payload;
        private UUID outboxEntryId;
    }

    @lombok.Data
    public static class MarkSentRequest {
        private String providerMessageId;
    }

    @lombok.Data
    public static class MarkFailedRequest {
        private String errorMessage;
    }

    @lombok.Data
    public static class NotificationStatsResponse {
        private long queuedCount;
        private long sentCount;
        private long deliveredCount;
        private long failedCount;
        private long totalCount;

        public NotificationStatsResponse(NotificationLogService.NotificationStats stats) {
            this.queuedCount = stats.getQueuedCount();
            this.sentCount = stats.getSentCount();
            this.deliveredCount = stats.getDeliveredCount();
            this.failedCount = stats.getFailedCount();
            this.totalCount = stats.getTotalCount();
        }
    }
}
