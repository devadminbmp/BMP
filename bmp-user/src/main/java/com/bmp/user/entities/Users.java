package com.bmp.user.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for user_schema.users.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "users", schema = "user_schema")
@Getter
public class Users {

    @Id
    private UUID id;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Setter
    @Column(name = "name", length = 120)
    private String name;
    @Setter
    @Column(name = "gender", length = 10)
    private String gender;
    @Setter
    @Column(name = "age")
    private int age;
    @Setter
    @Column(name = "email", length = 160)
    private String email;
    @Setter
    @Column(name = "profile_photo_url", length = 500)
    private String profilePhotoUrl;
    @Setter
    @Column(name = "hair_type", length = 30)
    private String hairType;
    @Setter
    @Column(name = "hair_length", length = 30)
    private String hairLength;
    @Column(name = "default_role", nullable = false, length = 20)
    private String defaultRole;
    @Column(name = "is_verified", nullable = false)
    private boolean isVerified;
    /** V004 (Session 13): NULL = active. Soft deactivation, reversed automatically on the
     * user's next successful OTP login (see bmp-auth's AuthService) — Instagram-style,
     * not a permanent deletion. */
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Users() {} // JPA

    public Users(String phone, String name, String gender, int age, String email, String profilePhotoUrl, String hairType, String hairLength, String defaultRole, boolean isVerified) {
        this.id = UuidV7.generate();
        this.phone = phone;
        this.name = name;
        this.gender = gender;
        this.age = age;
        this.email = email;
        this.profilePhotoUrl = profilePhotoUrl;
        this.hairType = hairType;
        this.hairLength = hairLength;
        this.defaultRole = defaultRole;
        this.isVerified = isVerified;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }

    /** Session 13: the user has re-chosen which of their held roles is the default they
     * log in as (the "stylist who also books as a customer" case from CONTEXT.md Module 1).
     * Validated against user_roles by the caller (UserService.setDefaultRole), not here. */
    public void setDefaultRole(String defaultRole) { this.defaultRole = defaultRole; }

    public void deactivate() {
        this.deactivatedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void reactivate() {
        this.deactivatedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isDeactivated() { return deactivatedAt != null; }
}
