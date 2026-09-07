package com.bmp.auth.entities;

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
    @Column(name = "email", length = 160)
    private String email;
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

    /**
     * Session 43 (V005) — when this code was successfully redeemed; NULL means never used.
     *
     * <p>Before this column, a verified OTP kept working for the rest of its 5-minute TTL: the
     * row was read, matched and left untouched, so the same six digits could be replayed. The
     * "one-time" in one-time password was a description of intent, not a property of the code.
     * Email being the only live delivery channel makes that worse than it sounds — the code sits
     * in an inbox that may be open on a shared screen or forwarded.
     */
    @Column(name = "consumed_at")
    private Instant consumedAt;

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

    /** Session 6: dual-channel OTP — email is known (looked up from bmp-user for an
     * existing user, or supplied fresh on signup) at request time, so it can be delivered
     * alongside the SMS/WhatsApp send instead of only ever going to a phone. */
    public OtpRequests(String phone, String email, String otpHash, int attempts, Instant lockedUntil, Instant expiresAt) {
        this(phone, otpHash, attempts, lockedUntil, expiresAt);
        this.email = email;
    }

    public UUID getId() { return id; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getOtpHash() { return otpHash; }
    public int getAttempts() { return attempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConsumedAt() { return consumedAt; }

    /** True once this code has been redeemed — it must never authenticate anyone again. */
    public boolean isConsumed() { return consumedAt != null; }

    /**
     * Mark this code as spent. Deliberately NOT a plain setter: there is exactly one legitimate
     * transition (unused → used, once, now), and a setter would invite "un-consuming" a code,
     * which is the one thing this column exists to prevent. Idempotent rather than throwing, so
     * a retried transaction can't fail on its own second pass.
     */
    public void markConsumed() {
        if (consumedAt == null) {
            this.consumedAt = Instant.now();
        }
    }
    public void setPhone(String phone) { this.phone = phone; }
    public void setEmail(String email) { this.email = email; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

}
