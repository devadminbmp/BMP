package com.bmp.auth.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending self-service contact change, waiting on a code. V006, Session 65.
 *
 * <h2>Why this is not a row in {@code otp_requests}</h2>
 * A login code and a contact-change code must never be interchangeable. Sharing the table would
 * mean the login verifier had to remember to exclude the other kind, and the day somebody forgets,
 * a code issued to confirm an email change signs somebody in. A separate table cannot be got wrong
 * by omission — the login path never reads this one.
 *
 * <h2>What the code proves depends on where it was sent</h2>
 * <pre>
 *   changing EMAIL → code goes to the NEW address   → proves they own it. Strong.
 *   changing PHONE → code goes to the address ON FILE → proves they are the account holder ONLY.
 * </pre>
 * The second is weaker and cannot be improved today: SMS is undeliverable until DLT registration
 * completes, so nothing can be sent to a number nobody has verified. {@link #sentToEmail} records
 * which case a given row was, so the record says what was actually proved rather than implying
 * both were equal.
 */
@Entity
@Table(name = "contact_change_request", schema = "user_schema")
public class ContactChangeRequest {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Canonical E.164, or null when only the email is changing. */
    @Column(name = "new_phone", length = 20)
    private String newPhone;

    @Column(name = "new_email", length = 160)
    private String newEmail;

    /** Where the code actually went. See the class javadoc — this is not always the new address. */
    @Column(name = "sent_to_email", nullable = false, length = 160)
    private String sentToEmail;

    /** bcrypt. The plaintext code is never stored and never logged. */
    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Non-null once redeemed. Single-use is enforced by reading this, not by deleting the row. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ContactChangeRequest() {} // JPA

    public ContactChangeRequest(UUID userId, String newPhone, String newEmail,
                                 String sentToEmail, String codeHash, Instant expiresAt) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.newPhone = newPhone;
        this.newEmail = newEmail;
        this.sentToEmail = sentToEmail;
        this.codeHash = codeHash;
        this.attempts = 0;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getNewPhone() { return newPhone; }
    public String getNewEmail() { return newEmail; }
    public String getSentToEmail() { return sentToEmail; }
    public String getCodeHash() { return codeHash; }
    public int getAttempts() { return attempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void recordFailedAttempt() { this.attempts++; }

    /**
     * Mark redeemed. Named for what it guarantees rather than as a setter, because the guarantee —
     * this code can never be used again — is the whole reason the column exists.
     */
    public void consume() { this.consumedAt = Instant.now(); }

    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExpired() { return expiresAt.isBefore(Instant.now()); }
}
