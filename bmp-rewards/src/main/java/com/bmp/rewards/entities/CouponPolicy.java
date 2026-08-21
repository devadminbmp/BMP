package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A configurable limit on what support may issue (rewards_schema.coupon_policy, V003).
 *
 * <p>These live in the database rather than as constants so they can be raised at 9am on a bad
 * morning without a deploy — and so that changing them is itself an audited action rather than
 * a commit nobody notices.
 */
@Entity
@Table(name = "coupon_policy", schema = "rewards_schema")
public class CouponPolicy {

    @Id
    private UUID id;

    @Column(name = "policy_key", nullable = false, length = 60)
    private String policyKey;

    @Column(name = "policy_value", nullable = false)
    private String policyValue;

    @Column(name = "description")
    private String description;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CouponPolicy() {} // JPA

    public CouponPolicy(String policyKey, String policyValue, String description, UUID updatedBy) {
        this.id = UuidV7.generate();
        this.policyKey = policyKey;
        this.policyValue = policyValue;
        this.description = description;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPolicyKey() { return policyKey; }
    public String getPolicyValue() { return policyValue; }
    public String getDescription() { return description; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String value, UUID by) {
        this.policyValue = value;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }
}
