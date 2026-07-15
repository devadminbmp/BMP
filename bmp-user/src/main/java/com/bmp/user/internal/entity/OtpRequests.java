package com.bmp.user.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for user_schema.otp_requests.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "otp_requests", schema = "user_schema")
public class OtpRequests {

    @Id
    private UUID id;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Column(name = "otp_hash", nullable = false, length = 255)
    private String otpHash;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OtpRequests() {} // JPA

    public OtpRequests(String phone, String otpHash, int attempts, Instant lockedUntil, Instant expiresAt) {
        this.id = UuidV7.generate();
        this.phone = phone;
        this.otpHash = otpHash;
        this.attempts = attempts;
        this.lockedUntil = lockedUntil;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPhone() { return phone; }
    public String getOtpHash() { return otpHash; }
    public int getAttempts() { return attempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
