package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A one-time code that lets a new employee set their own password (admin_schema.staff_activation,
 * V004).
 *
 * <p>The master admin creates the ACCOUNT and receives this code; the employee redeems it and
 * chooses a password nobody else has ever seen. That's the whole point — see V004's header for
 * why an admin-chosen password quietly destroys the audit log's value as evidence.
 *
 * <p>Stored hashed, single-use, 48-hour expiry, with attempts counted.
 */
@Entity
@Table(name = "staff_activation", schema = "admin_schema")
@Getter
public class StaffActivation {

    @Id
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "code_hash", nullable = false, length = 120)
    private String codeHash;

    /** 'activation' for a new employee, 'reset' when re-issued for someone locked out. */
    @Column(name = "purpose", nullable = false, length = 20)
    private String purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Setter
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Setter
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StaffActivation() {} // JPA

    public StaffActivation(UUID staffId, String codeHash, String purpose, Instant expiresAt, UUID createdBy) {
        this.id = UuidV7.generate();
        this.staffId = staffId;
        this.codeHash = codeHash;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.attemptCount = 0;
        this.createdAt = Instant.now();
    }

    public boolean isRedeemable() {
        return consumedAt == null && expiresAt.isAfter(Instant.now());
    }
}
