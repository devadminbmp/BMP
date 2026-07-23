package com.bmp.notification.services;

import com.bmp.notification.dto.NotificationDtos.LogRequest;
import com.bmp.notification.dto.NotificationDtos.LogResponse;
import com.bmp.notification.dto.NotificationDtos.StatsResponse;
import com.bmp.notification.entities.NotificationLog;
import com.bmp.notification.repositories.NotificationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BMP-30: notification_log CRUD. Session 6: no longer purely a log — markSent/markFailed
 * below are called by NotificationDispatcher right after a real email/SMS send attempt, so
 * status now actually moves off "queued" (see NotificationLog.markSent/markFailed).
 *
 * <p>Session 7: getByRecipient/getPendingNotifications/getStats/markDelivered ported from
 * Shivam's parallel BMP-30 work (built against a stale pre-flatten package layout with
 * mismatched column names — rebuilt here against the real V002 schema, see
 * NotificationLogRepository's javadoc).
 */
@Service
public class NotificationLogService {

    private final NotificationLogRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public NotificationLogService(NotificationLogRepository repo) {
        this.repo = repo;
    }

    public LogResponse log(LogRequest req) {
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(req.payload() == null ? Map.of() : req.payload());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD_JSON");
        }
        // status stays "queued" — no real send integration exists yet (Phase 3: BMP-7/8/9)
        NotificationLog entity = new NotificationLog(
                req.recipientUserId(), req.channel(), req.templateCode(), payloadJson,
                "queued", null, null, null, null);
        entity = repo.save(entity);
        return toResponse(entity);
    }

    public LogResponse get(UUID id) {
        return repo.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOTIFICATION_LOG_NOT_FOUND"));
    }

    public void markSent(UUID id) {
        repo.findById(id).ifPresent(NotificationLog::markSent);
    }

    public void markFailed(UUID id, String reason) {
        repo.findById(id).ifPresent(n -> n.markFailed(reason));
    }

    /** Ported from Session 7 BMP-30 — no provider webhook calls this yet (Phase 3), but the
     * transition exists so one can be wired up without touching this service again. */
    public LogResponse markDelivered(UUID id) {
        NotificationLog n = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOTIFICATION_LOG_NOT_FOUND"));
        n.markDelivered();
        return toResponse(repo.save(n));
    }

    public List<LogResponse> list(UUID recipientUserId, String channel) {
        List<NotificationLog> all = repo.findAll();
        return all.stream()
                .filter(n -> recipientUserId == null || recipientUserId.equals(n.getRecipientUserId()))
                .filter(n -> channel == null || channel.equals(n.getChannel()))
                .map(this::toResponse)
                .toList();
    }

    /** Ported from Session 7 BMP-30 — paginated recipient history (the plain list() above
     * loads every row into memory, fine for a demo but not for a real recipient inbox). */
    public Page<LogResponse> getByRecipient(UUID recipientUserId, Pageable pageable) {
        return repo.findByRecipientUserId(recipientUserId, pageable).map(this::toResponse);
    }

    /** Ported from Session 7 BMP-30 — "still in flight" (queued or sent, not yet
     * delivered/failed) notifications for a recipient. */
    public Page<LogResponse> getPendingNotifications(UUID recipientUserId, Pageable pageable) {
        return repo.findPendingForRecipient(recipientUserId, pageable).map(this::toResponse);
    }

    /** Ported from Session 7 BMP-30 — counts by status, for an ops dashboard. */
    public StatsResponse getStats() {
        long queued = repo.countByStatus("queued");
        long sent = repo.countByStatus("sent");
        long delivered = repo.countByStatus("delivered");
        long failed = repo.countByStatus("failed");
        return new StatsResponse(queued, sent, delivered, failed, queued + sent + delivered + failed);
    }

    /** Ported from Session 7 BMP-30 — notifications stuck in "queued" past maxAgeMinutes,
     * for a future retry job (none exists yet — this just surfaces candidates). */
    public List<LogResponse> findStalledNotifications(int maxAgeMinutes) {
        Instant threshold = Instant.now().minusSeconds((long) maxAgeMinutes * 60);
        return repo.findStalledQueued(threshold).stream().map(this::toResponse).toList();
    }

    private LogResponse toResponse(NotificationLog n) {
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(n.getPayload(), Map.class);
        } catch (Exception e) {
            payload = Map.of();
        }
        return new LogResponse(n.getId(), n.getRecipientUserId(), n.getChannel(), n.getTemplateCode(),
                payload, n.getStatus(), n.getProviderMessageId(), n.getErrorReason(), n.getCreatedAt(),
                n.getSentAt(), n.getDeliveredAt());
    }
}
