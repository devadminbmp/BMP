package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // ---- Session 20 additions (V003) ------------------------------------------------------

    /**
     * Who the actor WAS, denormalised.
     *
     * <p>A log that records only {@code actor_id} becomes unreadable the moment that staff
     * member leaves and their row is edited or their email is reused. The whole value of an
     * audit trail is being intelligible a year later, to someone who wasn't there.
     */
    @Column(name = "actor_email", length = 160)
    private String actorEmail;

    @Column(name = "actor_role", length = 20)
    private String actorRole;

    /**
     * WHY the action was taken. Required by the service layer for anything that reveals
     * personal data — the deterrent against misuse of an internal console isn't permissions
     * (staff need the access) but a named, reasoned entry colleagues can read.
     */
    @Column(name = "justification")
    private String justification;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {} // JPA

    /** Pre-Session-20 constructor, kept so existing call sites compile unchanged. */
    public AuditLog(String actorType, UUID actorId, String action, String entityType, UUID entityId, String metadata, String ipAddress) {
        this(actorType, actorId, action, entityType, entityId, metadata, ipAddress, null, null, null);
    }

    public AuditLog(String actorType, UUID actorId, String action, String entityType, UUID entityId,
                    String metadata, String ipAddress, String actorEmail, String actorRole, String justification) {
        this.id = UuidV7.generate();
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.metadata = metadata;
        this.ipAddress = ipAddress;
        this.actorEmail = actorEmail;
        this.actorRole = actorRole;
        this.justification = justification;
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
    public String getActorEmail() { return actorEmail; }
    public String getActorRole() { return actorRole; }
    public String getJustification() { return justification; }
    public Instant getCreatedAt() { return createdAt; }
}
