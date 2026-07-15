package com.bmp.user.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for user_schema.users.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "users", schema = "user_schema")
public class Users {

    @Id
    private UUID id;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Column(name = "name", length = 120)
    private String name;
    @Column(name = "gender", length = 10)
    private String gender;
    @Column(name = "age")
    private int age;
    @Column(name = "email", length = 160)
    private String email;
    @Column(name = "profile_photo_url", length = 500)
    private String profilePhotoUrl;
    @Column(name = "hair_type", length = 30)
    private String hairType;
    @Column(name = "hair_length", length = 30)
    private String hairLength;
    @Column(name = "default_role", nullable = false, length = 20)
    private String defaultRole;
    @Column(name = "is_verified", nullable = false)
    private boolean isVerified;
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

    public UUID getId() { return id; }
    public String getPhone() { return phone; }
    public String getName() { return name; }
    public String getGender() { return gender; }
    public int getAge() { return age; }
    public String getEmail() { return email; }
    public String getProfilePhotoUrl() { return profilePhotoUrl; }
    public String getHairType() { return hairType; }
    public String getHairLength() { return hairLength; }
    public String getDefaultRole() { return defaultRole; }
    public boolean isVerified() { return isVerified; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
