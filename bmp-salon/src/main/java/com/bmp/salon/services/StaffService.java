package com.bmp.salon.services;

import com.bmp.salon.client.UserServiceClient;
import com.bmp.salon.dto.StaffDtos.*;
import com.bmp.salon.entities.SalonStaff;
import com.bmp.salon.entities.StaffInvites;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.SalonStaffRepository;
import com.bmp.salon.repositories.StaffInvitesRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session 6: salon_schema.salon_staff / staff_invites. Owner seats are created directly by
 * {@link SalonService#create} (the creating user becomes OWNER, no invite needed); manager
 * seats go through an invite token an owner generates and shares out-of-band (SMS/WhatsApp
 * — actual send wired once bmp-notification's channel is chosen, see bmp-auth's OtpRequested
 * for the pattern this will reuse).
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    private final SalonStaffRepository staff;
    private final StaffInvitesRepository invites;
    private final StylistSalonRepository stylistSalons;
    private final UserServiceClient users;
    /** V025 (Session 51) — a suspended stylist may not redeem an invite. */
    private final StylistSuspensionGuard suspensions;
    /** V028 (Session 65) — for the salon's NAME in the invite email. "A salon" is not an invitation. */
    private final com.bmp.salon.repositories.SalonRepository salons;
    /** V028 (Session 65) — emailing the invite code. See {@link #createInvite}. */
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    private final SecureRandom random = new SecureRandom();

    private static final int INVITE_TTL_HOURS = 48;

    /** Stored uppercase in salon_staff.role; bmp-user stores its role values lowercase. */
    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_MANAGER = "MANAGER";

    public StaffService(SalonStaffRepository staff, StaffInvitesRepository invites,
                        StylistSalonRepository stylistSalons, UserServiceClient users,
                        StylistSuspensionGuard suspensions,
                        com.bmp.salon.repositories.SalonRepository salons,
                        com.bmp.common.outbox.OutboxPublisher outbox) {
        this.salons = salons;
        this.outbox = outbox;
        this.staff = staff;
        this.invites = invites;
        this.stylistSalons = stylistSalons;
        this.users = users;
        this.suspensions = suspensions;
    }

    /**
     * Issue a one-time invite.
     *
     * <p>Session 17: the invite carries a ROLE. Same envelope, different contents on redemption:
     * {@code manager} creates a salon_staff seat (dashboard access, salon-scoped),
     * {@code stylist} creates a stylist_salon link (portable identity, not salon-scoped).
     *
     * <p>Re-inviting the same phone is allowed and creates a fresh token. That's deliberate:
     * "resend" is the most common follow-up action, and the older token simply expires on its
     * own 48h clock — or the issuer revokes it from the pending list.
     */
    @Transactional
    public InviteResponse createInvite(UUID salonId, CreateInviteRequest req) {
        String role = req.roleOrDefault();
        String token = randomToken();
        Instant expiresAt = Instant.now().plus(INVITE_TTL_HOURS, ChronoUnit.HOURS);
        String email = req.emailOrNull();   // blank and absent are the same thing — no address
        StaffInvites entry = new StaffInvites(salonId, req.phone(), token, "pending", expiresAt,
                role, req.inviteeName(), email);
        entry = invites.save(entry);

        // NOTE the token is NOT logged. It is a credential until redeemed or expired, and an
        // invite code sitting in an application log is an invite code anybody with log access can
        // redeem. The id is enough to correlate.
        log.info("Invite issued: salonId={} inviteId={} role={} phone={} email={} expiresAt={}",
                salonId, entry.getId(), role, req.phone(), email == null ? "none" : "supplied", expiresAt);

        if (email != null) {
            emailTheCode(entry);
        }
        return toInviteResponse(entry);
    }

    /**
     * Send the code to the address the owner gave us, and record that we did. V028 (Session 65).
     *
     * <h2>Non-fatal, deliberately, and loud</h2>
     * The invite is already saved and is the thing that matters — the owner can still read the
     * code off their screen and pass it on, which is exactly how this worked before today. Rolling
     * back a valid invitation because a mail server was slow would be absurd.
     *
     * <p>But it is logged at ERROR and {@code emailed_at} stays NULL, and those two facts are the
     * whole design: an owner looking at "invited, not joined yet" needs to be able to tell
     * "delivered, waiting on them" from "never went — you still have to send this yourself". A
     * timestamp written optimistically before the send would answer that question wrongly in
     * precisely the case where the answer matters.
     */
    private void emailTheCode(StaffInvites entry) {
        try {
            String salonName = salons.findById(entry.getSalonId())
                    .map(com.bmp.salon.entities.Salon::getName)
                    .orElse("A salon on BMP");

            outbox.publish(new com.bmp.common.events.StaffInviteIssued(
                    entry.getId(), entry.getSalonId(), salonName, entry.getRole(),
                    entry.getInviteeName(), entry.getInviteeEmail(), entry.getPhone(),
                    entry.getToken(), entry.getExpiresAt()));

            /*
             * Marked here rather than on a delivery receipt, and the distinction is worth being
             * honest about: this records "we handed it to the outbox", not "it landed in an inbox".
             * The outbox is transactional and retried, so a published event is a strong promise —
             * but a bounced address will not come back and clear this. The owner remains the
             * fallback channel, which is why the pending list says "sent" and never "delivered".
             */
            entry.markEmailed();
            invites.save(entry);
            log.info("Invite {} queued for email delivery to the address on file.", entry.getId());
        } catch (Exception e) {
            log.error("Invite {} was created but could not be emailed ({}). The code is valid and "
                    + "the owner can still share it by hand — emailed_at stays null so the UI says so.",
                    entry.getId(), e.toString());
        }
    }

    @Transactional
    public ConsumeInviteResponse consumeInvite(ConsumeInviteRequest req) {
        StaffInvites invite = invites.findByTokenAndStatus(req.token(), "pending")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVITE_NOT_FOUND_OR_ALREADY_USED"));

        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "INVITE_EXPIRED");
        }
        if (!invite.getPhone().equals(req.phone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVITE_PHONE_MISMATCH");
        }

        UUID userId = UUID.fromString(req.userId());
        UUID salonId = invite.getSalonId();

        // Session 17: one token type, two outcomes. The branch is on the invite's stored role,
        // NOT on anything the redeemer sends — otherwise a stylist invite could be redeemed for
        // a manager seat by whoever holds the token.
        if ("stylist".equalsIgnoreCase(invite.getRole())) {
            if (req.stylistId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "stylistId is required to consume a STYLIST invite");
            }
            /*
             * Session 48 — ONE SALON AT A TIME.
             *
             * V021 added a unique index over ACTIVE links, so a stylist already working elsewhere
             * cannot be linked here. Without this check the insert below would still be attempted
             * and fail with a raw constraint violation: a 500 mentioning
             * uq_stylist_one_active_salon, to somebody who just clicked a link in an invite email.
             *
             * Note the comment that used to sit here said a stylist "can work at more than one
             * salon over time". That is still true — and it is what the alumni rows record. What
             * changed is that "over time" is now enforced to mean sequentially, not at once.
             */
            // V025 — barred from the platform. An invite is one of four routes onto a team, and
            // an unenforced route is the whole suspension undone.
            suspensions.assertNotSuspended(req.stylistId(), "invite redemption");

            for (StylistSalon other : stylistSalons.findByStylistId(req.stylistId())) {
                if (other.isActive() && !other.getSalonId().equals(salonId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "STYLIST_AT_ANOTHER_SALON: this stylist already works at another salon "
                            + "on BMP. They need to leave there first — from their own profile — "
                            + "before this invite can be accepted.");
                }
            }

            StylistSalon existing = stylistSalons.findBySalonIdAndStylistId(salonId, req.stylistId())
                    .orElse(null);
            if (existing != null && existing.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_LINKED_TO_SALON");
            }

            if (existing != null) {
                // An alumnus of THIS salon, coming back. Reuse the row so their rating and review
                // count here survive; a fresh insert would reset both to zero and would now also
                // leave two rows for one employment.
                existing.setStatus(StylistSalon.ACTIVE);
                existing.setLeftAt(null);
                existing.setIsAvailableToday(true);
                stylistSalons.save(existing);
                log.info("Stylist {} REJOINED salon {} via invite — previous link reactivated.",
                        req.stylistId(), salonId);
            } else {
                // A stylist_salon link, NOT a salon_staff seat: a stylist is never salon-scoped in
                // their JWT. Their profile and reviews travel with them — that's the portable
                // identity model this table exists to express. is_available_today starts true so
                // they're bookable on day one.
                stylistSalons.save(new StylistSalon(req.stylistId(), salonId, StylistSalon.ACTIVE,
                        null, 0, true, Instant.now(), null));
            }
            invite.setStatus("accepted");
            log.info("STYLIST invite consumed: salonId={} stylistId={} userId={}", salonId, req.stylistId(), userId);
            return new ConsumeInviteResponse(salonId, "stylist");
        }

        if (staff.existsBySalonIdAndUserId(salonId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_STAFF_AT_SALON");
        }

        staff.save(new SalonStaff(salonId, userId, "MANAGER"));
        invite.setStatus("accepted");
        log.info("MANAGER invite consumed: salonId={} userId={}", salonId, userId);

        return new ConsumeInviteResponse(salonId, "manager");
    }

    @Transactional
    public void addOwner(UUID salonId, UUID ownerUserId) {
        staff.save(new SalonStaff(salonId, ownerUserId, "OWNER"));
    }

    /**
     * Does this user already OWN a salon? Session 48.
     *
     * <p>Deliberately narrower than {@link #lookupByUserId}, which returns the newest staff row of
     * any role. Using that for the create-salon guard would stop a stylist or a manager from ever
     * opening their own place — a real person with a real reason, blocked by a check meant for
     * duplicates. Owner rows only.
     */
    public boolean ownsASalon(UUID userId) {
        return staff.findByUserId(userId).stream()
                .anyMatch(s -> "owner".equalsIgnoreCase(s.getRole()));
    }

    public Optional<StaffLookupResponse> lookupByUserId(UUID userId) {
        return staff.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(s -> new StaffLookupResponse(s.getSalonId(), s.getRole()));
    }

    public List<SalonStaff> listForSalon(UUID salonId) {
        return staff.findBySalonId(salonId);
    }

    // ------------------------------------------------------------------
    // Session 15 — owner-facing team management (list / add / remove).
    //
    // The whole area rests on one asymmetry: bmp-salon owns the SEAT
    // (salon_staff), bmp-user owns the IDENTITY and the ROLE that ends up in
    // the JWT. Every mutation below therefore has to move both, and the order
    // matters (see removeStaff).
    // ------------------------------------------------------------------

    /**
     * The salon's roster, enriched with names and phones from bmp-user.
     *
     * <p>Enrichment is best-effort per member: if bmp-user is down or a user row vanished, that
     * member comes back with null name/phone instead of the whole call failing. An owner
     * looking at their team during a partial outage should see a degraded list, not an error.
     */
    public List<StaffMemberResponse> listStaff(UUID salonId, UUID callerUserId) {
        return staff.findBySalonId(salonId).stream()
                .map(seat -> {
                    String name = null;
                    String phone = null;
                    try {
                        UserServiceClient.UserDto u = users.getUserById(seat.getUserId()).getBody();
                        if (u != null) {
                            name = u.name();
                            phone = u.phone();
                        }
                    } catch (Exception e) {
                        log.warn("staff roster: could not enrich userId={} for salonId={} ({}) — "
                                + "returning the seat without profile fields", seat.getUserId(), salonId, e.toString());
                    }
                    boolean isSelf = seat.getUserId().equals(callerUserId);
                    boolean removable = !ROLE_OWNER.equalsIgnoreCase(seat.getRole());
                    return new StaffMemberResponse(seat.getId(), seat.getUserId(), seat.getRole(),
                            name, phone, seat.getCreatedAt(), isSelf, removable);
                })
                .toList();
    }

    /**
     * Promote an EXISTING BMP user to manager of this salon, by phone.
     *
     * <p>Three writes, in this order:
     * <ol>
     *   <li>{@code salon_staff} MANAGER seat — the authorization fact this service owns.</li>
     *   <li>bmp-user {@code user_roles} grant — so the role survives independently of the seat
     *       and shows up in "what roles do I hold".</li>
     *   <li>bmp-user {@code default_role = manager} — <b>this is the one people forget.</b> The
     *       JWT's role claim is minted from the user's DEFAULT role, not from their seat. Skip
     *       this and the new manager logs in, gets a token that says "customer", and is denied
     *       by every {@code hasRole('MANAGER')} guard while the database insists they're staff.</li>
     * </ol>
     *
     * <p>Their existing access token keeps its old claims until it expires (~15 min) or they
     * refresh — deliberate, and the reason the UI tells the owner the person may need to sign
     * out and back in.
     */
    @Transactional
    public StaffMemberResponse addManager(UUID salonId, AddStaffRequest req, UUID callerUserId) {
        UserServiceClient.UserDto user;
        try {
            user = users.getUserByPhone(req.phone()).getBody();
        } catch (Exception e) {
            log.warn("addManager: phone lookup failed for salonId={} ({})", salonId, e.toString());
            user = null;
        }
        if (user == null) {
            // No account yet — the invite flow is the right path, and the message says so
            // rather than leaving the owner guessing why a valid phone "doesn't exist".
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "NO_BMP_ACCOUNT_FOR_PHONE — send them an invite code instead");
        }
        if (user.deactivatedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "USER_DEACTIVATED");
        }
        if (staff.existsBySalonIdAndUserId(salonId, user.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_STAFF_AT_SALON");
        }
        // One seat per person, platform-wide: the JWT carries a single salonId, so a second
        // seat elsewhere would silently shadow this one at the next token mint.
        if (staff.findFirstByUserIdOrderByCreatedAtDesc(user.id()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_STAFF_AT_ANOTHER_SALON");
        }

        SalonStaff seat = staff.save(new SalonStaff(salonId, user.id(), ROLE_MANAGER));

        try {
            users.addRole(user.id(), new UserServiceClient.CreateRoleRequest("manager", salonId));
        } catch (Exception e) {
            // 409 = they already hold it (e.g. a retry after a partial failure). Anything else
            // is worth shouting about, but the seat is what authorizes them, so don't roll back.
            log.warn("addManager: grant manager role failed for userId={} ({}) — seat kept", user.id(), e.toString());
        }
        try {
            users.setDefaultRole(user.id(), new UserServiceClient.DefaultRoleRequest("manager"));
        } catch (Exception e) {
            log.error("addManager: could not switch default role for userId={} ({}). Their next "
                    + "token will still claim '{}' and manager screens will 403 until this is fixed.",
                    user.id(), e.toString(), user.defaultRole());
        }

        log.info("Manager added: salonId={} userId={} by ownerUserId={}", salonId, user.id(), callerUserId);
        return new StaffMemberResponse(seat.getId(), seat.getUserId(), seat.getRole(),
                user.name(), user.phone(), seat.getCreatedAt(), false, true);
    }

    /**
     * Remove a manager's seat.
     *
     * <p>Guards, all deliberate:
     * <ul>
     *   <li>The seat must belong to THIS salon (repository lookup is salon-scoped), so a staff
     *       id guessed from another salon is a 404, not a cross-tenant delete.</li>
     *   <li>OWNER seats can't be removed — that would leave a salon nobody can administer.
     *       Transferring ownership is a different, deliberately harder operation.</li>
     *   <li>You can't remove yourself, which is the same protection stated plainly.</li>
     * </ul>
     *
     * <p>Then the reverse of {@link #addManager}, and the ORDER IS FORCED: bmp-user returns 409
     * if you revoke a role that is still the user's default. So switch the default back to
     * customer first, then revoke. Doing it the other way round fails every time.
     *
     * <p>Revocation is not instant: their existing access token stays valid for its remaining
     * ~15 minutes. It's the next refresh that mints a customer token (staff-lookup finds no
     * seat). Acceptable for a scheduling app; if it ever isn't, the fix is refresh-token
     * revocation on removal, not a shorter TTL everywhere.
     */
    @Transactional
    public void removeStaff(UUID salonId, UUID staffId, UUID callerUserId) {
        SalonStaff seat = staff.findByIdAndSalonId(staffId, salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND_IN_THIS_SALON"));

        if (ROLE_OWNER.equalsIgnoreCase(seat.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "CANNOT_REMOVE_OWNER — transfer ownership first");
        }
        if (seat.getUserId().equals(callerUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CANNOT_REMOVE_YOURSELF");
        }

        UUID userId = seat.getUserId();
        staff.delete(seat);

        try {
            // 1. Default role back to customer — MUST precede the revoke (see Javadoc).
            users.setDefaultRole(userId, new UserServiceClient.DefaultRoleRequest("customer"));
            // 2. Drop the manager role for THIS salon. Matching on salonId matters: a person
            //    who managed two salons over time can hold more than one historical grant.
            users.listRoles(userId).stream()
                    .filter(r -> "manager".equalsIgnoreCase(r.role()) && salonId.equals(r.salonId()))
                    .forEach(r -> users.removeRole(userId, r.id()));
        } catch (Exception e) {
            // The seat is already gone, which is what actually gates access (staff-lookup
            // returns nothing on their next refresh). A stale role row is untidy, not unsafe.
            log.error("removeStaff: seat deleted for userId={} salonId={} but role cleanup failed ({}). "
                    + "Their user_roles row may need manual tidying.", userId, salonId, e.toString());
        }

        log.info("Manager removed: salonId={} userId={} by ownerUserId={}", salonId, userId, callerUserId);
    }

    /**
     * Invites issued but never redeemed — the "sent, still waiting" list.
     *
     * @param role optional filter ({@code manager} | {@code stylist}); null means all. The
     *             controller passes {@code stylist} for manager callers, since the manager
     *             roster is the owner's business.
     */
    public List<InviteResponse> listPendingInvites(UUID salonId, String role) {
        return invites.findBySalonIdAndStatusOrderByCreatedAtDesc(salonId, "pending").stream()
                .filter(i -> role == null || role.equalsIgnoreCase(i.getRole()))
                .map(this::toInviteResponse)
                .toList();
    }

    /** Pre-Session-17 signature — all pending invites, no role filter. */
    public List<InviteResponse> listPendingInvites(UUID salonId) {
        return listPendingInvites(salonId, null);
    }

    /**
     * Revoke a pending invite — the "I sent that to the wrong number" button.
     *
     * <p>Marked {@code revoked} rather than deleted: {@code consumeInvite} only ever matches
     * status {@code pending}, so a revoked token is instantly dead, and we keep the audit trail
     * of who was invited to a salon. ({@code status} is a 10-char column; "revoked" fits.)
     */
    @Transactional
    public void revokeInvite(UUID salonId, UUID inviteId, UUID callerUserId, boolean callerIsOwner) {
        StaffInvites invite = invites.findByIdAndSalonId(inviteId, salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND_IN_THIS_SALON"));

        // Mirror of the create rule: managers deal in stylists, owners deal in managers.
        if (!callerIsOwner && !"stylist".equalsIgnoreCase(invite.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ONLY_OWNER_CAN_REVOKE_MANAGER_INVITES");
        }

        if (!"pending".equalsIgnoreCase(invite.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "INVITE_NOT_PENDING — it was already " + invite.getStatus());
        }
        invite.setStatus("revoked");
        log.info("Invite revoked: salonId={} inviteId={} by ownerUserId={}", salonId, inviteId, callerUserId);
    }

    private String randomToken() {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private InviteResponse toInviteResponse(StaffInvites e) {
        return new InviteResponse(e.getId(), e.getSalonId(), e.getPhone(), e.getToken(), e.getStatus(),
                e.getExpiresAt(), e.getRole(), e.getInviteeName(),
                // V028 — both, so the pending list can distinguish "no address given" (owner must
                // pass the code on) from "sent" (they have it) from "address given, send failed".
                e.getInviteeEmail(), e.getEmailedAt());
    }
}
