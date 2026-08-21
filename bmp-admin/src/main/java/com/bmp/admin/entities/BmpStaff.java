package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for admin_schema.bmp_staff.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "bmp_staff", schema = "admin_schema")
@Getter
public class BmpStaff {

    @Id
    private UUID id;

    @Setter
    @Column(name = "name", nullable = false, length = 120)
    private String name;
    @Setter
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Setter
    @Column(name = "email", length = 160)
    private String email;
    @Setter
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Setter
    @Column(name = "role", nullable = false, length = 20)
    private String role;
    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Setter
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // ---- Session 20: 2FA + lockout (see V003) ------------------------------------------

    /**
     * Base32 TOTP secret (RFC 6238). NULL until the staff member enrols.
     *
     * <p>Login is refused past the password step while this is null, so 2FA cannot be skipped —
     * not by the staff member, and not by whoever created the account. A password alone
     * protecting a console that reads every customer's personal data isn't defensible, and
     * password reuse is universal.
     */
    @Setter
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Setter
    @Column(name = "totp_enrolled_at")
    private Instant totpEnrolledAt;

    @Setter
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Setter
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Who created this account — the first question asked after an incident. */
    @Setter
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BmpStaff() {} // JPA

    public BmpStaff(String name, String phone, String email, String passwordHash, String role, String status, Instant lastLoginAt) {
        this.id = UuidV7.generate();
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.lastLoginAt = lastLoginAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }

    /** True once 2FA is set up. Until then the console must not let them past login. */
    public boolean isTotpEnrolled() {
        return totpSecret != null && !totpSecret.isBlank();
    }

    /** True while a brute-force lockout is in force. */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
}
