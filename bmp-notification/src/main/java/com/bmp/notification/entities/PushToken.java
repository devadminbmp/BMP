package com.bmp.notification.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One device's push delivery address. Session 64 (V004).
 *
 * <p>See the migration header for why this lives in bmp-notification rather than on the user, and
 * why a dead token is disabled rather than deleted.
 */
@Entity
@Table(name = "push_token", schema = "notification_schema")
@Getter
public class PushToken {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, length = 200)
    private String token;

    @Column(name = "platform", nullable = false, length = 10)
    private String platform;

    @Column(name = "device_id", length = 120)
    private String deviceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_reason", length = 120)
    private String disabledReason;

    protected PushToken() { /* JPA */ }

    public PushToken(UUID userId, String token, String platform, String deviceId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.token = token;
        this.platform = platform;
        this.deviceId = deviceId;
        this.createdAt = Instant.now();
        this.lastSeenAt = this.createdAt;
    }

    /**
     * The same device, seen again — possibly by a DIFFERENT person.
     *
     * <p>Reassigning {@code userId} is the point, not an oversight. A phone handed from one person
     * to another keeps its token, and if this only refreshed the timestamp the new owner would
     * receive the previous owner's notifications. The unique index on `token` is what forces every
     * registration through here rather than allowing a second row.
     */
    public void seen(UUID userId, String platform, String deviceId) {
        this.userId = userId;
        this.platform = platform;
        this.deviceId = deviceId;
        this.lastSeenAt = Instant.now();
        // Revive it. A reinstall that returns the same token should start working again, and this
        // is why the row was kept rather than deleted.
        this.disabledAt = null;
        this.disabledReason = null;
    }

    /** Provider says it is gone, or the person signed out. Soft — see the migration header. */
    public void disable(String reason) {
        this.disabledAt = Instant.now();
        this.disabledReason = reason;
    }

    public boolean isLive() {
        return disabledAt == null;
    }
}
