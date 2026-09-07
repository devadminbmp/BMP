package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.staff_invites.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "staff_invites", schema = "salon_schema")
public class StaffInvites {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Column(name = "token", nullable = false, length = 64)
    private String token;
    @Column(name = "status", nullable = false, length = 10)
    private String status;
    /**
     * Session 17: 'manager' | 'stylist'. Decides what redeeming the token actually creates —
     * a salon_staff seat (dashboard access, salon-scoped) or a stylist_salon link (portable
     * identity, not salon-scoped). Defaults to 'manager' in the DB so pre-existing invites
     * keep their original meaning.
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role;
    /** Display-only, for the issuer's pending list. Never trusted as the invitee's real name. */
    @Column(name = "invitee_name", length = 120)
    private String inviteeName;
    /**
     * V028 (Session 65) — where to email the code.
     *
     * <p>Darshan: "we need take stylist email id also hence we can send him invitation and code
     * beautifully in email but u r taking only name and number". Before this the code was only
     * ever shown on the owner's screen, which means it reaches the stylist by being read out over
     * a phone — and that is how a 32-character token gets mistyped and the app gets blamed.
     *
     * <p>NULLABLE on purpose. An owner inviting the person standing in front of them has no
     * reason to know their email, and demanding one would block the commonest case to serve the
     * convenient one.
     */
    @Column(name = "invitee_email", length = 160)
    private String inviteeEmail;
    /**
     * When we managed to send it. Deliberately distinct from "an email was supplied", so an owner
     * can tell "I never gave an address" apart from "we tried and it didn't go" — they are the
     * fallback delivery channel and need to know which situation they are in.
     */
    @Column(name = "emailed_at")
    private Instant emailedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StaffInvites() {} // JPA

    /** Pre-Session-17 constructor — kept so existing call sites still compile; assumes MANAGER. */
    public StaffInvites(UUID salonId, String phone, String token, String status, Instant expiresAt) {
        this(salonId, phone, token, status, expiresAt, "manager", null);
    }

    /**
     * Pre-V028 constructor — kept so existing call sites still compile; no email, so the code is
     * shared by the owner exactly as it was before Session 65.
     */
    public StaffInvites(UUID salonId, String phone, String token, String status, Instant expiresAt,
                        String role, String inviteeName) {
        this(salonId, phone, token, status, expiresAt, role, inviteeName, null);
    }

    public StaffInvites(UUID salonId, String phone, String token, String status, Instant expiresAt,
                        String role, String inviteeName, String inviteeEmail) {
        this.inviteeEmail = inviteeEmail;
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.phone = phone;
        this.token = token;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.role = role;
        this.inviteeName = inviteeName;
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getPhone() { return phone; }
    public String getToken() { return token; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRole() { return role; }
    public String getInviteeName() { return inviteeName; }
    public String getInviteeEmail() { return inviteeEmail; }
    public Instant getEmailedAt() { return emailedAt; }

    /**
     * Record that the code was emailed. V028.
     *
     * <p>Set only on a CONFIRMED send, never optimistically before calling the mail sender. The
     * whole value of this column is telling the owner whether they still have to pass the code on
     * themselves, and a timestamp written before the send would answer that question wrongly in
     * exactly the case where the answer matters.
     */
    public void markEmailed() { this.emailedAt = Instant.now(); }

    /** Session 6: pending -> accepted/declined/expired transition, needed once invites are
     * actually consumed (StaffService.consumeInvite). Not a free-form setter — status is a
     * one-way state machine in practice, same spirit as OutboxEntry.markProcessed(). */
    public void setStatus(String status) { this.status = status; }
}
