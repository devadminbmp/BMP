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
}
