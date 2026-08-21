package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A staff refresh session (admin_schema.staff_session, V003).
 *
 * <p><b>selector.verifier split</b>, the same design bmp-auth uses for customers: the client
 * holds {@code selector.verifier}; we store the selector in the clear (so lookup is a single
 * indexed hit, with no timing signal from scanning) and only a HASH of the verifier. Someone
 * who steals a dump of this table gets no usable sessions.
 *
 * <p>Staff sessions are deliberately short-lived, and revocable server-side — a customer stays
 * signed in for 30 days, but a support console session that outlives someone's shift on a
 * shared office machine is a liability. IP and user agent are recorded so "whose session was
 * that?" has an answer.
 */
@Entity
@Table(name = "staff_session", schema = "admin_schema")
@Getter
public class StaffSession {

    @Id
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "selector", nullable = false, length = 40)
    private String selector;

    @Column(name = "verifier_hash", nullable = false, length = 120)
    private String verifierHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Setter
    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected StaffSession() {} // JPA

    public StaffSession(UUID staffId, String selector, String verifierHash, Instant expiresAt,
                        String ipAddress, String userAgent) {
        this.id = UuidV7.generate();
        this.staffId = staffId;
        this.selector = selector;
        this.verifierHash = verifierHash;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.revoked = false;
        this.createdAt = Instant.now();
    }

    public boolean isUsable() {
        return !revoked && expiresAt.isAfter(Instant.now());
    }
}
