package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A self-registered stylist asking an owner to add them to a salon. V020 (Session 48).
 *
 * <h2>The owner's acceptance is the only thing that creates a link</h2>
 * "I work at Lumière" typed into a form is a claim. If signing up created the stylist_salon link
 * directly, anybody could put themselves on any salon's public page and into its booking picker.
 * This row is the claim; {@code accept()} is the salon agreeing to it, and only that produces the
 * link.
 */
@Entity
@Table(name = "stylist_join_request", schema = "salon_schema")
@Getter
@NoArgsConstructor
public class StylistJoinRequest {

    public static final String PENDING = "pending";
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String WITHDRAWN = "withdrawn";

    /**
     * WHICH WAY was this proposed. V028, Session 65.
     *
     * <p>A salon inviting a stylist and a stylist asking a salon are the same agreement from
     * opposite ends — same statuses, same one-salon rule, same resulting link. What differs is
     * WHO MAY ACCEPT, and that is the only reason this is stored.
     */
    public static final String FROM_STYLIST = "stylist_to_salon";
    public static final String FROM_SALON = "salon_to_stylist";

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** The stylist's note — "Priya knows me, I've been here since March". */
    @Column(name = "message", length = 500)
    private String message;

    /** The owner's reason on a decline, shown to the stylist so "no" is not a dead end. */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /**
     * The USER id of whoever decided — not a staff row id.
     *
     * <p>Staff rows are removed when somebody leaves the salon; the record of who made a decision
     * has to outlive their employment or the audit trail develops holes exactly where it matters.
     */
    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** {@link #FROM_STYLIST} or {@link #FROM_SALON}. V028. */
    @Column(name = "direction", nullable = false, length = 20)
    private String direction = FROM_STYLIST;

    /** The owner or manager who sent an invitation. Null when the stylist asked first. */
    @Column(name = "invited_by_user_id")
    private UUID invitedByUserId;

    /** The stylist ASKS a salon. The salon decides. V020. */
    public StylistJoinRequest(UUID stylistId, UUID salonId, String message) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.salonId = salonId;
        this.status = PENDING;
        this.message = message;
        this.direction = FROM_STYLIST;
        this.createdAt = Instant.now();
    }

    /**
     * The salon INVITES a stylist who already has an account. The stylist decides. V028.
     *
     * <p>Static factory rather than a second constructor with the same erasure — two
     * {@code (UUID, UUID, String)} constructors could not coexist, and even if they could, a
     * caller picking the wrong one would silently reverse who is allowed to accept.
     */
    public static StylistJoinRequest invitation(UUID stylistId, UUID salonId,
                                                 String message, UUID invitedByUserId) {
        StylistJoinRequest r = new StylistJoinRequest(stylistId, salonId, message);
        r.direction = FROM_SALON;
        r.invitedByUserId = invitedByUserId;
        return r;
    }

    /** True when the SALON proposed this, so the stylist is the one who answers. */
    public boolean isInvitation() {
        return FROM_SALON.equals(direction);
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    /**
     * Record the owner's decision.
     *
     * <p>Refuses to re-decide. Two managers opening the inbox at once would otherwise both press a
     * button and the second would silently overwrite the first — including overwriting an accept
     * with a decline, on a stylist who has already been told they are in.
     */
    public void decide(boolean accepted, UUID deciderUserId, String note) {
        if (!isPending()) {
            throw new IllegalStateException(
                    "Join request " + id + " is already " + status + " and cannot be decided again.");
        }
        this.status = accepted ? ACCEPTED : DECLINED;
        this.decidedAt = Instant.now();
        this.decidedBy = deciderUserId;
        this.decisionNote = note;
    }

    /** The stylist changed their mind before anybody answered. Distinct from being declined. */
    public void withdraw() {
        if (!isPending()) {
            throw new IllegalStateException(
                    "Join request " + id + " is already " + status + " and cannot be withdrawn.");
        }
        this.status = WITHDRAWN;
        this.decidedAt = Instant.now();
    }
}
