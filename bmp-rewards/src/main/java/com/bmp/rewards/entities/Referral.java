package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for rewards_schema.referral.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "referral", schema = "rewards_schema")
public class Referral {

    @Id
    private UUID id;

    @Column(name = "referrer_user_id", nullable = false)
    private UUID referrerUserId;
    @Column(name = "referee_user_id", nullable = false)
    private UUID refereeUserId;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "referrer_reward_paise", nullable = false)
    private Money referrerRewardPaise;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "referee_reward_paise", nullable = false)
    private Money refereeRewardPaise;
    @Column(name = "referred_at", nullable = false)
    private Instant referredAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "fraud_reason", length = 20)
    private String fraudReason;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected Referral() {} // JPA

    public Referral(UUID referrerUserId, UUID refereeUserId, Money referrerRewardPaise, Money refereeRewardPaise, Instant referredAt, Instant expiresAt, String fraudReason, Instant completedAt) {
        this.id = UuidV7.generate();
        this.referrerUserId = referrerUserId;
        this.refereeUserId = refereeUserId;
        this.referrerRewardPaise = referrerRewardPaise;
        this.refereeRewardPaise = refereeRewardPaise;
        this.referredAt = referredAt;
        this.expiresAt = expiresAt;
        this.fraudReason = fraudReason;
        this.completedAt = completedAt;

    }

    public UUID getId() { return id; }
    public UUID getReferrerUserId() { return referrerUserId; }
    public UUID getRefereeUserId() { return refereeUserId; }
    public Money getReferrerRewardPaise() { return referrerRewardPaise; }
    public Money getRefereeRewardPaise() { return refereeRewardPaise; }
    public Instant getReferredAt() { return referredAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getFraudReason() { return fraudReason; }
    public Instant getCompletedAt() { return completedAt; }

    /**
     * Mark the reward as paid. Session 64.
     *
     * <p>A named mutator rather than a setter, and it REFUSES to run twice. This is the idempotency
     * guard for the whole payout: `booking.completed` is an at-least-once Kafka event, so a
     * redelivery — a consumer restart, a rebalance, a retried outbox row — will call this again with
     * the same referral. Without the guard, each redelivery credits two wallets again.
     *
     * <p>A setter would express none of that and would invite exactly the "just set it" call that
     * causes the double credit. Returning false is the caller's signal to skip the money.
     *
     * @return true if this call is the one that completed it; false if it was already complete.
     */
    public boolean markCompleted() {
        if (this.completedAt != null) return false;
        this.completedAt = Instant.now();
        return true;
    }
}
