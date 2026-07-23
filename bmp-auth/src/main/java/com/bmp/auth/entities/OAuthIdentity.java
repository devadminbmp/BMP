package com.bmp.auth.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for user_schema.oauth_identity (Session 6 — see V003 migration).
 * Links a bmp user to a Google account by the stable `sub` claim, not by email — emails can
 * change or be reused; `sub` never does. Customer-facing only for now (see AuthController's
 * /oauth2/google endpoint).
 */
@Entity
@Table(name = "oauth_identity", schema = "user_schema")
public class OAuthIdentity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;
    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;
    @Column(name = "email", length = 160)
    private String email;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OAuthIdentity() {} // JPA

    public OAuthIdentity(UUID userId, String provider, String providerSubject, String email) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.email = email;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getProviderSubject() { return providerSubject; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
}
