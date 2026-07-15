package com.bmp.user.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for user_schema.refresh_tokens.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "refresh_tokens", schema = "user_schema")
public class RefreshTokens {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;
    @Column(name = "device_fingerprint", length = 255)
    private String deviceFingerprint;
    @Column(name = "revoked", nullable = false)
    private boolean revoked;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RefreshTokens() {} // JPA

    public RefreshTokens(UUID userId, String tokenHash, String deviceFingerprint, boolean revoked, Instant expiresAt) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.deviceFingerprint = deviceFingerprint;
        this.revoked = revoked;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getDeviceFingerprint() { return deviceFingerprint; }
    public boolean isRevoked() { return revoked; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
