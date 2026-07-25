package com.bmp.user.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for user_schema.onboarding_state.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "onboarding_state", schema = "user_schema")
public class OnboardingState {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_json", nullable = false, columnDefinition = "jsonb")
    private String stateJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OnboardingState() {} // JPA

    public OnboardingState(UUID userId, String stateJson) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.stateJson = stateJson;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getStateJson() { return stateJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Session 13: upsert path — the client re-saves the whole blob on every onboarding
     * step, so replacement (not merge) is the intended semantics. */
    public void replaceState(String stateJson) {
        this.stateJson = stateJson;
        this.updatedAt = Instant.now();
    }
}
