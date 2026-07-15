package com.bmp.user.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for user_schema.user_roles.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "user_roles", schema = "user_schema")
public class UserRoles {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "role", nullable = false, length = 20)
    private String role;
    @Column(name = "salon_id")
    private UUID salonId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserRoles() {} // JPA

    public UserRoles(UUID userId, String role, UUID salonId) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.role = role;
        this.salonId = salonId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public UUID getSalonId() { return salonId; }
    public Instant getCreatedAt() { return createdAt; }
}
