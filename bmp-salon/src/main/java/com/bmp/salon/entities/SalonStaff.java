package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_staff.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_staff", schema = "salon_schema")
public class SalonStaff {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "role", nullable = false, length = 20)
    private String role;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SalonStaff() {} // JPA

    public SalonStaff(UUID salonId, UUID userId, String role) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.userId = userId;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
