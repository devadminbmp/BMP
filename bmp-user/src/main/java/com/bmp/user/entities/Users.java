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

    /**
     * V005 (Session 56) widened this from 20 to 64. A real E.164 number never needs more than 20,
     * but an anonymised row stores {@code ANON-<uuid>} here — 41 characters — and the mapping must
     * match the column or Hibernate's validation fails at startup.
     */
    @Column(name = "phone", nullable = false, length = 64)
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
    /**
     * V005 (Session 56) — personal data erased under a deletion request. TERMINAL.
     *
     * <p>Not the same state as {@link #deactivatedAt}, and the difference is the reason this is a
     * separate column: deactivation is reversible and bmp-auth restores it on the next successful
     * OTP login. Anonymisation has nothing to restore. Anything that reactivates an account must
     * check this first, or a "deleted" account comes back.
     */
    @Column(name = "anonymised_at")
    private Instant anonymisedAt;

    @Column(name = "anonymised_reason", length = 40)
    private String anonymisedReason;

    /**
     * V006 (Session 65) — blocked BY STAFF. NULL = not blocked.
     *
     * <p>Deliberately not {@link #deactivatedAt}, because the two say opposite things about the
     * person's wishes. Deactivation means "I want a break", and bmp-auth reversing it on their
     * next login is the intended behaviour. A block means "we have stopped you", and reversing it
     * on their next login is a bug — which is precisely what happened when Block was first wired
     * to {@code deactivate()}: the button worked, the audit entry was written, and the person
     * logged straight back in.
     *
     * <p>An account can hold both states at once without either destroying the other.
     */
    @Column(name = "blocked_at")
    private Instant blockedAt;

    /** The bmp_staff id of whoever blocked them. No FK — different service, different schema. */
    @Column(name = "blocked_by")
    private UUID blockedBy;

    /** Why. Shown to staff who look the account up; never shown to the blocked person. */
    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

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

    /**
     * Change the login identity. Session 65.
     *
     * <p>A NAMED mutator, not a setter, and the name says what it costs: after this the old number
     * can no longer sign in and the new one can. Anything that can do that deserves to be called
     * something a reader will stop at, rather than blending into a wall of generated setters.
     *
     * <p>The caller is responsible for canonicalising and for checking uniqueness —
     * UserService.changeContact does both, and uk_users_phone (V004) is the backstop.
     */
    public void changeLoginPhone(String canonicalPhone) {
        this.phone = canonicalPhone;
    }

    /**
     * Change where notifications and OTPs go.
     *
     * <p>Lower stakes than the phone — email is a contact field, not the identity — but still the
     * address a login code is mailed to, which is why it is an administered action rather than
     * something a caller can set freely.
     */
    public void changeContactEmail(String email) {
        this.email = email;
    }

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

    /**
     * Block this account. Session 65.
     *
     * <p>Does NOT touch {@code deactivatedAt}, on purpose. Setting both would mean the next login
     * clears one of them and leaves the account in a half-state that reads as "blocked but the
     * block was partly lifted". The block stands entirely on its own column, so the reactivation
     * path can go on doing exactly what it did before without knowing this feature exists.
     *
     * <p>Idempotent in effect but NOT silent about it — {@code UserService.block} refuses to
     * re-block, so that a second block cannot quietly overwrite the first one's reason and lose
     * why the account was stopped in the first place.
     */
    public void block(UUID staffId, String reason) {
        this.blockedAt = Instant.now();
        this.blockedBy = staffId;
        this.blockedReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Lift a block.
     *
     * <p>Clears the reason too. The audit log holds the durable record of what happened and why —
     * leaving a stale reason on an account that is no longer blocked would mean a staff member
     * reading the row sees "fraudulent bookings" against somebody who has been cleared.
     */
    public void unblock() {
        this.blockedAt = null;
        this.blockedBy = null;
        this.blockedReason = null;
        this.updatedAt = Instant.now();
    }

    public boolean isBlocked() { return blockedAt != null; }

    public Instant getBlockedAt() { return blockedAt; }
    public UUID getBlockedBy() { return blockedBy; }
    public String getBlockedReason() { return blockedReason; }

    /** Terminal. Nothing here can be restored, and login must be refused. */
    public boolean isAnonymised() { return anonymisedAt != null; }

    public Instant getAnonymisedAt() { return anonymisedAt; }
    public String getAnonymisedReason() { return anonymisedReason; }

    /**
     * Erase the personal data, keep the id. V005 (Session 56).
     *
     * <h2>Why the row is not deleted</h2>
     * {@code booking.customer_id}, {@code invoice.customer_id}, {@code review.author_user_id} and
     * {@code coupon_usage.user_id} are cross-service logical references with no FK to stop a
     * delete orphaning them. Erasure obligations cover personal data, not the commercial record of
     * a transaction that really happened — which tax law requires be kept regardless.
     *
     * <h2>The phone becomes a tombstone, freeing the real number</h2>
     * {@code phone} is NOT NULL and UNIQUE because it is the login identity, so it cannot be
     * nulled. It becomes {@code ANON-<id>}: unique, undialable, obviously not real — and it
     * RELEASES the original number so the same person can sign up again later. Without that,
     * exercising a deletion right would permanently bar them from the platform.
     *
     * <p>Idempotent: erasing an already-erased account is a no-op rather than an error, because a
     * retried compliance job must not fail on work it already did.
     */
    public void anonymise(String reason) {
        if (anonymisedAt != null) return;
        this.name = null;
        this.email = null;
        this.gender = null;
        this.age = 0;
        this.profilePhotoUrl = null;
        this.hairType = null;
        this.hairLength = null;
        this.phone = "ANON-" + this.id;
        // Deactivated as well as anonymised: every existing "is this account usable?" check reads
        // deactivatedAt, and they must all say no without each one having to learn a new field.
        this.deactivatedAt = Instant.now();
        this.anonymisedAt = Instant.now();
        this.anonymisedReason = reason;
    }
}
