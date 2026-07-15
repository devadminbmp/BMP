package com.bmp.admin.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for admin_schema.audit_log.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "audit_log", schema = "admin_schema")
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;
    @Column(name = "actor_id")
    private UUID actorId;
    @Column(name = "action", nullable = false, length = 100)
    private String action;
    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;
    @Column(name = "entity_id", nullable = false)
    private UUID entityId;
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {} // JPA

    public AuditLog(String actorType, UUID actorId, String action, String entityType, UUID entityId, String metadata, String ipAddress) {
        this.id = UuidV7.generate();
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.metadata = metadata;
        this.ipAddress = ipAddress;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getMetadata() { return metadata; }
    public String getIpAddress() { return ipAddress; }
    public Instant getCreatedAt() { return createdAt; }
}
