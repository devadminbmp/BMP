package com.bmp.rewards.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One version of the referral offer. Append-only. Session 64 (V006).
 *
 * <h2>No setters, at all</h2>
 * A change is a new row, never an edit. That is not tidiness — a referral is a promise made at a
 * moment, and editing the offer in place destroys the only record of what was promised. See the
 * V006 header.
 *
 * <p>The absence of setters is what makes that structural rather than a convention somebody
 * follows until they are in a hurry.
 */
@Entity
@Table(name = "referral_program", schema = "rewards_schema")
public class ReferralProgram {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "referrer_reward_paise", nullable = false)
    private long referrerRewardPaise;

    @Column(name = "referee_reward_paise", nullable = false)
    private long refereeRewardPaise;

    @Column(name = "referrer_enabled", nullable = false)
    private boolean referrerEnabled;

    @Column(name = "referee_enabled", nullable = false)
    private boolean refereeEnabled;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "changed_by_staff_id")
    private UUID changedByStaffId;

    @Column(name = "changed_by_email", length = 160)
    private String changedByEmail;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReferralProgram() { /* JPA */ }

    public ReferralProgram(long referrerRewardPaise, long refereeRewardPaise,
                            boolean referrerEnabled, boolean refereeEnabled,
                            Instant effectiveFrom, UUID changedByStaffId, String changedByEmail,
                            String note) {
        this.id = UuidV7.generate();
        this.referrerRewardPaise = referrerRewardPaise;
        this.refereeRewardPaise = refereeRewardPaise;
        this.referrerEnabled = referrerEnabled;
        this.refereeEnabled = refereeEnabled;
        this.effectiveFrom = effectiveFrom;
        this.changedByStaffId = changedByStaffId;
        this.changedByEmail = changedByEmail;
        this.note = note;
        this.createdAt = Instant.now();
    }

    /**
     * What to FREEZE onto a new referral for the referrer.
     *
     * <p>Zero when the side is switched off. This is the single place enabled-ness and amount are
     * read together — everywhere downstream sees one number, so a referral row cannot say ₹150 and
     * mean nothing.
     */
    public long referrerPayoutPaise() {
        return referrerEnabled ? referrerRewardPaise : 0L;
    }

    /** As {@link #referrerPayoutPaise()}, for the person who was referred. */
    public long refereePayoutPaise() {
        return refereeEnabled ? refereeRewardPaise : 0L;
    }

    /** True when neither side pays anything — the programme is effectively off. */
    public boolean isEffectivelyOff() {
        return referrerPayoutPaise() == 0 && refereePayoutPaise() == 0;
    }

    public UUID getId() { return id; }
    public long getReferrerRewardPaise() { return referrerRewardPaise; }
    public long getRefereeRewardPaise() { return refereeRewardPaise; }
    public boolean isReferrerEnabled() { return referrerEnabled; }
    public boolean isRefereeEnabled() { return refereeEnabled; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public UUID getChangedByStaffId() { return changedByStaffId; }
    public String getChangedByEmail() { return changedByEmail; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
