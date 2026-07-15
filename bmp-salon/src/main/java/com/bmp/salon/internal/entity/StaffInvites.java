package com.bmp.salon.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.staff_invites.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "staff_invites", schema = "salon_schema")
public class StaffInvites {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Column(name = "token", nullable = false, length = 64)
    private String token;
    @Column(name = "status", nullable = false, length = 10)
    private String status;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StaffInvites() {} // JPA

    public StaffInvites(UUID salonId, String phone, String token, String status, Instant expiresAt) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.phone = phone;
        this.token = token;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getPhone() { return phone; }
    public String getToken() { return token; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
