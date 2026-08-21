package com.bmp.admin.services;

import com.bmp.admin.dto.AdminDtos.AuditLogResponse;
import com.bmp.admin.entities.AuditLog;
import com.bmp.admin.repositories.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BMP-29 / BMP-3: audit_log is READ + system-insert only — no public POST/PUT/DELETE.
 * admin_schema.audit_log has REVOKE UPDATE, DELETE at the DB level (see V008 migration);
 * this service is the only writer, called as a side-effect of other actions (e.g. staff
 * status change), never directly from a client-facing endpoint.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditLogService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void record(String actorType, UUID actorId, String action, String entityType, UUID entityId,
                        Map<String, Object> metadata, String ipAddress) {
        record(actorType, actorId, action, entityType, entityId, metadata, ipAddress, null, null, null);
    }

    /**
     * Session 20: records WHO the actor was (denormalised, so the entry stays readable after
     * they leave) and WHY, for anything that exposes personal data.
     *
     * <p>Never throws. An audit write failing must not roll back the action it describes —
     * that would mean a logging outage silently blocks support work. Losing an entry is bad;
     * taking the console down is worse, and the error is logged loudly either way.
     */
    public void record(String actorType, UUID actorId, String action, String entityType, UUID entityId,
                        Map<String, Object> metadata, String ipAddress,
                        String actorEmail, String actorRole, String justification) {
        String metadataJson;
        try {
            metadataJson = mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            metadataJson = "{}";
        }
        try {
            repo.save(new AuditLog(actorType, actorId, action, entityType, entityId, metadataJson,
                    ipAddress, actorEmail, actorRole, justification));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuditLogService.class).error(
                    "AUDIT WRITE FAILED — action={} actor={} target={}/{}. The action itself "
                    + "succeeded; this entry is lost.", action, actorEmail, entityType, entityId, e);
        }
    }

    public List<AuditLogResponse> find(String entityType, UUID entityId) {
        return repo.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(this::toResponse)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private AuditLogResponse toResponse(AuditLog a) {
        Map<String, Object> metadata;
        try {
            metadata = mapper.readValue(a.getMetadata(), Map.class);
        } catch (Exception e) {
            metadata = Map.of();
        }
        return new AuditLogResponse(a.getId(), a.getActorType(), a.getActorId(), a.getAction(),
                a.getEntityType(), a.getEntityId(), metadata, a.getCreatedAt());
    }
}
