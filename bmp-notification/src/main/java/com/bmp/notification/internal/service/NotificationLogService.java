package com.bmp.notification.internal.service;

import com.bmp.notification.internal.dto.NotificationDtos.LogRequest;
import com.bmp.notification.internal.dto.NotificationDtos.LogResponse;
import com.bmp.notification.internal.entity.NotificationLog;
import com.bmp.notification.internal.repository.NotificationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BMP-30: notification_log CRUD. Intentionally minimal — this is a log/queue table only.
 * NOTHING here actually sends a WhatsApp/SMS/push message; status stays "queued" forever
 * until Phase 3 (BMP-7 OutboxProcessor / BMP-8 / BMP-9) drains it. That is expected.
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

    public List<LogResponse> list(UUID recipientUserId, String channel) {
        List<NotificationLog> all = repo.findAll();
        return all.stream()
                .filter(n -> recipientUserId == null || recipientUserId.equals(n.getRecipientUserId()))
                .filter(n -> channel == null || channel.equals(n.getChannel()))
                .map(this::toResponse)
                .toList();
    }

    private LogResponse toResponse(NotificationLog n) {
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(n.getPayload(), Map.class);
        } catch (Exception e) {
            payload = Map.of();
        }
        return new LogResponse(n.getId(), n.getRecipientUserId(), n.getChannel(), n.getTemplateCode(),
                payload, n.getStatus(), n.getProviderMessageId(), n.getErrorReason(), n.getCreatedAt(), n.getSentAt());
    }
}
