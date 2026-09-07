package com.bmp.salon.services;

import com.bmp.salon.client.UserServiceClient;
import com.bmp.salon.entities.Salon;
import com.bmp.salon.entities.Stylist;
import com.bmp.salon.entities.StylistJoinRequest;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.SalonRepository;
import com.bmp.salon.repositories.StylistJoinRequestRepository;
import com.bmp.salon.repositories.StylistRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A stylist who signs themselves up, and asks to join a salon. Session 48.
 *
 * <h2>What changed, and why</h2>
 * Until now the only route in was an INVITE: the owner made a token, the stylist redeemed it, and
 * the salon link fell out of that as a side effect. Identity was a consequence of employment.
 *
 * <p>That has one real cost. A stylist who moves salon starts from nothing — their rating, their
 * review count and their speciality live on a row their old employer created. The person who did
 * the work does not own the record of having done it.
 *
 * <p>So a stylist can now create their own profile and ASK to join. The invite flow is untouched;
 * this is the other direction, for somebody who found BMP by themselves.
 *
 * <h2>The rule this class exists to enforce</h2>
 * <b>A stylist can never link themselves to a salon.</b> Every path that creates a
 * {@code stylist_salon} row here goes through an owner's acceptance. Without that, anyone could
 * put themselves on any salon's public page, into its stylist picker, and in front of its
 * customers — by typing a name into a form.
 */
@Service
public class StylistSelfService {

    private static final Logger log = LoggerFactory.getLogger(StylistSelfService.class);

    /** Kept short deliberately — see {@link #createProfile}. */
    private static final int MAX_SPECIALITY = 120;

    private final StylistRepository stylists;
    private final StylistSalonRepository links;
    private final StylistJoinRequestRepository requests;
    private final SalonRepository salons;
    private final UserServiceClient users;
    /** Session 49 — telling the stylist what the salon decided. See publishDecision. */
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    /** V025 (Session 51) — a suspended stylist may not be accepted onto a team. */
    private final StylistSuspensionGuard suspensions;

    public StylistSelfService(StylistRepository stylists, StylistSalonRepository links,
                               StylistJoinRequestRepository requests, SalonRepository salons,
                               UserServiceClient users,
                               com.bmp.common.outbox.OutboxPublisher outbox,
                               StylistSuspensionGuard suspensions) {
        this.suspensions = suspensions;
        this.stylists = stylists;
        this.links = links;
        this.requests = requests;
        this.salons = salons;
        this.users = users;
        this.outbox = outbox;
    }

    // ══ lookups shared with StylistLeaveService ═══════════════════════════════════════════════
    //
    // Session 49. StylistLeaveService needs the same three answers this class already resolves in
    // publishDecision — who is this stylist, what is their email, what is the salon called.
    // Exposed here rather than injecting three more repositories into that service, so there is
    // one place that knows how a stylist's contact details are found.

    /** By stylist id, not user id. Null when the row is gone — callers must tolerate that. */
    public Stylist stylistById(UUID stylistId) {
        return stylists.findById(stylistId).orElse(null);
    }

    /**
     * The user account behind a stylist profile, for their email.
     *
     * <p>Returns null rather than throwing on a failed call: this is only ever used to decorate a
     * notification, and a bmp-user outage must not take down leave approval with it.
     */
    public UserServiceClient.UserDto userContact(UUID userId) {
        try {
            return users.getUserById(userId).getBody();
        } catch (Exception e) {
            log.warn("Could not resolve user {} for a notification ({}).", userId, e.toString());
            return null;
        }
    }

    /** Never null — a notification that says "the salon" is better than one that says "null". */
    public String salonName(UUID salonId) {
        return salons.findById(salonId).map(Salon::getName).orElse("the salon");
    }

    // ══ profile ═══════════════════════════════════════════════════════════════════════════════

    public Stylist myProfile(UUID userId) {
        return stylists.findFirstByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "NO_STYLIST_PROFILE: this account has not registered as a stylist yet."));
    }

    /**
     * "I'm a stylist" — create the profile and grant the role.
     *
     * <h2>The role is granted BY THIS SERVICE, never by the user</h2>
     * bmp-user's {@code POST /users/{id}/roles} is {@code hasRole('SERVICE')} with a javadoc
     * saying roles are granted by real flows and never self-service. That rule is right and this
     * respects it: the customer app calls this endpoint, and bmp-salon — a service caller —
     * decides they have earned the role by completing this flow.
     *
     * <p>The difference matters. If the app called bmp-user directly with the user's own token,
     * the same call could grant {@code salon_owner}, or an admin role, by changing one string in
     * the request body. AUTHORISE THE PATH, THEN TRUST THE BODY is the hole this codebase keeps
     * finding; here the body never reaches the role endpoint at all.
     *
     * <h2>They keep being a customer</h2>
     * The role is ADDED, not swapped. A stylist books haircuts too, and taking that away as the
     * price of registering would be a strange thing to do to somebody who just told us more about
     * themselves.
     */
    @Transactional
    public Stylist createProfile(UUID userId, String name, String speciality) {
        stylists.findFirstByUserId(userId).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "STYLIST_PROFILE_EXISTS: this account already has a stylist profile.");
        });

        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "NAME_REQUIRED: customers see this name next to their booking.");
        }

        Stylist s = new Stylist(userId, name.trim(),
                // A brand-new stylist has NO rating — null, not zero. Zero renders as one star and
                // would make every new stylist look terrible on their first day. The customer
                // surfaces already read null as "new" (see NearbySalonResponse.rating).
                null, 0, false);
        s.setSpeciality(trimTo(speciality, MAX_SPECIALITY));
        stylists.save(s);

        /*
         * Best-effort on the role, deliberately, and loud when it fails.
         *
         * The profile is the thing the person just created and can see; a role that failed to
         * grant is recoverable (they log in as a customer and the dashboard is missing) whereas
         * rolling the profile back would lose what they typed and tell them nothing. But it MUST
         * be loud — a stylist without the role cannot reach their own dashboard, and the symptom
         * from their side is "the button did nothing".
         */
        try {
            users.addRole(userId, new UserServiceClient.CreateRoleRequest("stylist", null));
            log.info("User {} registered as a stylist (profile {}).", userId, s.getId());
        } catch (Exception e) {
            log.error("Stylist profile {} was created for user {} but the 'stylist' ROLE could not "
                    + "be granted ({}). They cannot reach the stylist dashboard until it is — "
                    + "re-grant it via bmp-user.", s.getId(), userId, e.toString());
        }
        return s;
    }

    /** Editable with no salon attached — the profile is theirs, not the salon's. */
    @Transactional
    public Stylist updateProfile(UUID userId, String name, String speciality) {
        Stylist s = myProfile(userId);
        if (name != null && !name.isBlank()) s.setName(name.trim());
        // Null means "leave it"; blank means "clear it". Treating both as "leave it" would make
        // an emptied field impossible to save, which reads as the form being broken.
        if (speciality != null) s.setSpeciality(speciality.isBlank() ? null : trimTo(speciality, MAX_SPECIALITY));
        return stylists.save(s);
    }

    /**
     * Every salon this stylist has EVER been linked to — current first, then past.
     *
     * <p>Empty is the normal state before an owner accepts them, and the UI must say so rather
     * than rendering a blank dashboard. Past salons are included on purpose: that list is the
     * stylist's work history, and it is the thing that survives them changing jobs.
     */
    public List<StylistSalon> myLinks(UUID userId) {
        return links.findByStylistId(myProfile(userId).getId()).stream()
                // Active first; then most recently joined. Without an explicit order this comes
                // back in whatever order the rows happen to sit in, and "where do I work?" ends up
                // below three salons they left years ago.
                .sorted(Comparator
                        .comparing(StylistSalon::isActive).reversed()
                        .thenComparing(StylistSalon::getJoinedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * The one salon they work at right now, if any.
     *
     * <p>Returns an Optional rather than a list because after V021 there can only be one — and
     * writing it as a list here is how a second one quietly becomes acceptable again later.
     */
    public Optional<StylistSalon> activeLink(UUID stylistId) {
        List<StylistSalon> active = links.findByStylistId(stylistId).stream()
                .filter(StylistSalon::isActive)
                .toList();
        if (active.size() > 1) {
            // V021 makes this impossible at the database level, so reaching it means the index is
            // missing on this environment. Loud, because the symptom otherwise is a stylist
            // appearing on two salons' teams and nobody knowing why.
            log.error("Stylist {} has {} ACTIVE salon links — uq_stylist_one_active_salon is not "
                    + "present on this database. Check that V021 ran.", stylistId, active.size());
        }
        return active.stream().findFirst();
    }

    /**
     * One line of a stylist's CV.
     *
     * @param salonName resolved here rather than left to the client — a work history rendered as a
     *                  column of UUIDs is not a work history. Falls back to a placeholder if the
     *                  salon row is gone, because a deleted salon must not erase the fact that the
     *                  stylist worked there.
     * @param current   true for the one salon they work at now
     */
    public record WorkHistoryEntry(UUID salonId, String salonName, String salonReference,
                                    boolean current, Instant joinedAt, Instant leftAt,
                                    BigDecimal salonRating, int salonReviewCount) {}

    /**
     * Everywhere this stylist has worked, current first.
     *
     * <p>This is the list the user asked for when they said the account "remains after leaving one
     * salon later he join other salon so experience maintained". The experience is not a number we
     * carry forward — it is these rows, kept.
     */
    public List<WorkHistoryEntry> myWorkHistory(UUID userId) {
        return myLinks(userId).stream().map(l -> {
            Salon s = salons.findById(l.getSalonId()).orElse(null);
            return new WorkHistoryEntry(
                    l.getSalonId(),
                    s != null ? s.getName() : "A salon no longer on BMP",
                    s != null ? s.getReference() : null,
                    l.isActive(), l.getJoinedAt(), l.getLeftAt(),
                    l.getSalonRating(), l.getSalonReviewCount());
        }).toList();
    }

    /**
     * "I'm not in today." Session 48.
     *
     * <h2>Why this needed its own endpoint</h2>
     * {@code PUT /api/v1/salons/{salonId}/stylists/{stylistId}/available-today} already exists and
     * lists STYLIST in its roles — but it also requires
     * {@code principal.salonId() != null && equals(#salonId)}, and <b>a stylist's JWT never has a
     * salonId</b>. bmp-auth's {@code resolveSalonScope} excludes stylists on purpose, because a
     * stylist is a portable profile rather than a salon seat.
     *
     * <p>So that guard could never pass for the very role it names. A stylist flipping "not in
     * today" got a 403, which is the fastest lever they have for stopping new bookings when
     * something has gone wrong in their day.
     *
     * <p>Resolving the salon from their active link fixes it without weakening the salon-scoped
     * endpoint, which is still correct for an owner or manager toggling somebody else.
     */
    @Transactional
    public StylistSalon setMyAvailabilityToday(UUID userId, boolean available) {
        Stylist me = myProfile(userId);
        StylistSalon link = activeLink(me.getId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT,
                        "NOT_AT_A_SALON: you're not on a salon's team, so there's nothing to "
                        + "take bookings for yet."));
        link.setIsAvailableToday(available);
        links.save(link);
        log.info("Stylist {} set available_today={} at salon {}", me.getId(), available, link.getSalonId());
        return link;
    }

    // ══ leaving ═══════════════════════════════════════════════════════════════════════════════

    /**
     * The stylist resigns.
     *
     * <h2>Why a stylist may do this without the owner's agreement</h2>
     * The alternative is that leaving requires the owner to press a button, and an owner who is
     * annoyed, busy, or gone simply never presses it — the stylist is then stuck at a salon they
     * do not work at, unable to join anywhere else, with their name in front of that salon's
     * customers. Employment on BMP is not a lock, and the person doing the work gets to end it.
     *
     * <p>What they cannot do is erase it. The row becomes {@code alumni}; the salon keeps the
     * record, and so does the stylist.
     *
     * @param reason free text, logged only — kept because "why did my stylist vanish?" is the
     *               first thing the owner will ask support
     */
    @Transactional
    public StylistSalon leaveCurrentSalon(UUID userId, String reason) {
        Stylist me = myProfile(userId);
        StylistSalon link = activeLink(me.getId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT,
                        "NOT_AT_A_SALON: you're not currently on any salon's team."));

        link.leave(Instant.now());
        links.save(link);
        log.info("Stylist {} LEFT salon {} (reason: {}). Link kept as alumni — profile, rating and "
                + "reviews are unaffected.", me.getId(), link.getSalonId(),
                reason == null || reason.isBlank() ? "none given" : trimTo(reason, 500));
        return link;
    }

    /*
     * The OWNER's side of leaving is StylistCrudService.markAlumni, reached by
     * POST /api/v1/salons/{salonId}/stylists/{stylistId}/alumni. It predates this class and is
     * deliberately NOT reimplemented here — two methods ending employment is how "removed" quietly
     * becomes two different things. Both now go through StylistSalon.leave().
     */

    // ══ join requests ═════════════════════════════════════════════════════════════════════════

    /**
     * Ask a salon to add you.
     *
     * <p>Refuses if already linked or already asking. Both would otherwise be silent no-ops that
     * leave the stylist tapping a button that appears to do nothing.
     */
    @Transactional
    public StylistJoinRequest requestToJoin(UUID userId, UUID salonId, String message) {
        Stylist me = myProfile(userId);

        salons.findById(salonId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        /*
         * One salon at a time — checked HERE so the stylist is told before they wait a week for an
         * answer, and again in decide() so an owner is never handed a request they cannot honour.
         *
         * The database has the last word (V021's unique index); these two checks exist for the
         * error message, not for the guarantee.
         */
        activeLink(me.getId()).ifPresent(existing -> {
            if (existing.getSalonId().equals(salonId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "ALREADY_AT_SALON: you're already on this salon's team.");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ALREADY_AT_ANOTHER_SALON: you can only work at one salon on BMP at a time. "
                    + "Leave your current salon first — your profile, rating and reviews stay "
                    + "with you.");
        });

        requests.findFirstByStylistIdAndSalonIdAndStatus(me.getId(), salonId, StylistJoinRequest.PENDING)
                .ifPresent(r -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "REQUEST_PENDING: you've already asked this salon — they haven't "
                            + "answered yet.");
                });

        StylistJoinRequest r = new StylistJoinRequest(me.getId(), salonId, trimTo(message, 500));
        requests.save(r);
        log.info("Stylist {} asked to join salon {}", me.getId(), salonId);
        return r;
    }

    // ══ V028 — THE SALON INVITES A STYLIST WHO ALREADY HAS AN ACCOUNT ═════════════════════════
    //
    // Darshan: "once he have account salon also search via number or email and ask him to join.
    // and stylist will get notification and accept."
    //
    // The missing third path. staff_invites covers salon → stranger (a code, redeemed at signup).
    // requestToJoin covers stylist → salon. Nothing covered salon → EXISTING stylist, which is why
    // a stylist could sign in holding an invite code and find nothing to accept anywhere.

    /** A stylist a salon might invite, found by phone or email. */
    public record InviteCandidate(UUID stylistId, UUID userId, String name, String speciality,
                                   String phoneMasked, boolean alreadyOnATeam,
                                   String currentSalonName, boolean suspended) {}

    /**
     * Find a stylist to invite, by phone OR email.
     *
     * <p>Returns empty rather than throwing when nobody matches: "no account with that email" is a
     * NORMAL answer here — it means "invite them by code instead" — not an error condition.
     *
     * <p>{@code alreadyOnATeam} is returned rather than filtering those people out. An owner who
     * searches for somebody and gets nothing assumes the search is broken; an owner who is told
     * "she's at Studio Nine" understands why they cannot have her and what to do about it.
     */
    public Optional<InviteCandidate> findInviteCandidate(String phoneOrEmail) {
        String q = phoneOrEmail == null ? "" : phoneOrEmail.trim();
        if (q.isEmpty()) return Optional.empty();

        UserServiceClient.UserDto user = null;
        try {
            var res = q.contains("@") ? users.getUserByEmail(q) : users.getUserByPhone(q);
            user = res == null ? null : res.getBody();
        } catch (Exception e) {
            /*
             * A 404 from bmp-user is the expected "no such account" and arrives here as an
             * exception from Feign. Anything else — bmp-user down, a bad service key — looks
             * identical from this side, so it is LOGGED rather than swallowed: "we could not find
             * them" and "we could not ask" must not be the same silent answer, which is the exact
             * bug Session 43 fixed in lookupUserByPhone.
             */
            log.info("No user found for '{}' while searching for a stylist to invite ({})",
                    q.contains("@") ? "an email" : "a phone", e.toString());
            return Optional.empty();
        }
        if (user == null) return Optional.empty();

        Optional<Stylist> stylist = stylists.findFirstByUserId(user.id());
        if (stylist.isEmpty()) {
            // They have a BMP account but have never set up a stylist profile. Not invitable yet —
            // and the caller needs to know the difference, so this is still an empty result with
            // a distinct message at the controller.
            return Optional.empty();
        }

        Stylist st = stylist.get();
        Optional<StylistSalon> link = activeLink(st.getId());
        return Optional.of(new InviteCandidate(
                st.getId(), user.id(), st.getName(), st.getSpeciality(),
                maskPhone(user.phone()),
                link.isPresent(),
                link.map(l -> salonName(l.getSalonId())).orElse(null),
                st.getSuspendedAt() != null));
    }

    /**
     * Invite a stylist onto this salon's team. The STYLIST decides.
     *
     * <p>Mirror image of {@link #requestToJoin}: same table, same statuses, opposite direction.
     * The one-salon rule is checked here for the MESSAGE and again when they accept for the
     * GUARANTEE — a stylist can join somewhere else in the days between.
     */
    @Transactional
    public StylistJoinRequest inviteStylist(UUID salonId, UUID stylistId, String message,
                                             UUID invitedByUserId) {
        Stylist st = stylists.findById(stylistId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));

        salons.findById(salonId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        // Barred from the platform — checked before an invitation is sent, so the owner is not
        // left waiting on somebody who could never accept.
        suspensions.assertNotSuspended(stylistId, "invited to a salon");

        activeLink(stylistId).ifPresent(existing -> {
            if (existing.getSalonId().equals(salonId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "ALREADY_AT_SALON: they're already on your team.");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ALREADY_AT_ANOTHER_SALON: they work at another salon on BMP. They can only "
                    + "be on one team at a time — they'd need to leave there first.");
        });

        /*
         * One open conversation per pair, whichever way it was started. Backed by
         * uq_join_request_one_open_per_pair (V028) — this check produces the readable message,
         * the index is what makes it true under two owners clicking at once.
         */
        requests.findFirstByStylistIdAndSalonIdAndStatus(stylistId, salonId, StylistJoinRequest.PENDING)
                .ifPresent(r -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            r.isInvitation()
                                ? "INVITE_PENDING: you've already invited them — they haven't answered yet."
                                : "REQUEST_PENDING: they've already asked to join. Answer that instead.");
                });

        StylistJoinRequest r = StylistJoinRequest.invitation(
                stylistId, salonId, trimTo(message, 500), invitedByUserId);
        requests.save(r);
        log.info("Salon {} invited stylist {} ({})", salonId, stylistId, st.getName());
        return r;
    }

    /** A stylist's INBOX: invitations waiting on them. Not the requests they sent. */
    public List<StylistJoinRequest> myInvitations(UUID userId) {
        return requests.findByStylistIdAndStatusAndDirectionOrderByCreatedAtDesc(
                myProfile(userId).getId(), StylistJoinRequest.PENDING, StylistJoinRequest.FROM_SALON);
    }

    /**
     * The stylist answers an invitation.
     *
     * <h2>Delegates to {@link #decide} on purpose</h2>
     * Accepting is the same act whoever performs it: check the suspension, check they are not on
     * another team, reuse the old link if they have worked here before so their salon rating
     * survives, create it otherwise. That logic is long, careful, and was written once.
     *
     * <p>Writing a second copy here is precisely how the two would drift — and Session 65 has
     * already found three places in this codebase where one rule existed twice and the weaker copy
     * was the one that ran. What differs is only WHO IS ALLOWED, and that is checked here before
     * delegating.
     */
    @Transactional
    public StylistJoinRequest respondToInvitation(UUID userId, UUID requestId,
                                                   boolean accept, String note) {
        Stylist me = myProfile(userId);
        StylistJoinRequest r = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND"));

        // Not yours — same 404 as a missing row, so an id cannot be probed for existence.
        if (!r.getStylistId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND");
        }
        /*
         * Direction is the authorisation here. A stylist must not be able to "accept" the request
         * THEY sent — that would be self-approving onto a salon's team without the owner ever
         * agreeing, which is the whole reason the direction column exists.
         */
        if (!r.isInvitation()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "NOT_AN_INVITATION: this is a request you sent. The salon answers it, not you.");
        }

        /*
         * Straight to the SHARED BODY, not through decide().
         *
         * decide() is the owner's door and now refuses invitations outright — it has to, or an
         * owner could accept their own invitation on the stylist's behalf. Calling it from here
         * would therefore make every stylist's Accept button throw FORBIDDEN.
         *
         * Both doors run the same logic underneath; only the guard in front differs, which is
         * exactly the shape this should have. One copy of the accept logic, two authorisations.
         */
        return applyDecision(r.getSalonId(), r, accept, userId, note);
    }

    /** "+91 98••• ••210" — enough to recognise somebody, not enough to enumerate. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return null;
        return phone.substring(0, Math.min(6, phone.length())) + "••• ••" + phone.substring(phone.length() - 3);
    }

    public List<StylistJoinRequest> myRequests(UUID userId) {
        return requests.findByStylistIdOrderByCreatedAtDesc(myProfile(userId).getId());
    }

    @Transactional
    public StylistJoinRequest withdraw(UUID userId, UUID requestId) {
        Stylist me = myProfile(userId);
        StylistJoinRequest r = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));
        // Ownership check. Without it any stylist could withdraw anyone's request by guessing an
        // id — the controller authorises the CALLER, and would otherwise trust the body's id.
        if (!r.getStylistId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND");
        }
        /*
         * V028 direction guard. "Withdraw" means TAKING BACK SOMETHING YOU PROPOSED, and after
         * V028 this stylist's rows include invitations they did not propose.
         *
         * Letting it through would be a quiet way around the note rule: declining an invitation
         * requires a brief reason, and withdrawing requires nothing — so the salon would watch
         * their invitation vanish with no explanation and no way to tell it apart from the stylist
         * cancelling their own request. Two words that both mean "it's gone" and mean quite
         * different things to the person watching.
         */
        if (r.isInvitation()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "USE_DECLINE: this is an invitation to you, not a request you sent. Decline it "
                    + "instead — the salon needs to know you said no.");
        }
        r.withdraw();
        return requests.save(r);
    }

    // ══ the owner's side ══════════════════════════════════════════════════════════════════════

    public List<StylistJoinRequest> pendingFor(UUID salonId) {
        return requests.findBySalonIdAndStatusOrderByCreatedAtAsc(salonId, StylistJoinRequest.PENDING);
    }

    public List<StylistJoinRequest> allFor(UUID salonId) {
        return requests.findBySalonIdOrderByCreatedAtDesc(salonId);
    }

    /**
     * The salon takes back an invitation it sent. Session 65 (V028).
     *
     * <p>Darshan: "stylist can leave salon or even salon can revoke". This is the second half of
     * that, before the stylist has answered — the mirror of a stylist withdrawing their own
     * request, and it has to exist for the same reason: an owner who invites the wrong Ravi has no
     * other way to take it back, and the unique index means that mistake BLOCKS them from inviting
     * the right one, because only one open conversation per pair is allowed.
     *
     * <p>Once withdrawn the row is resolved, the index frees up, and the pair can start again.
     *
     * @param salonId from the caller's token, never the body — so an owner can only ever reach
     *                invitations their own salon sent
     */
    @Transactional
    public StylistJoinRequest withdrawInvitation(UUID salonId, UUID requestId) {
        StylistJoinRequest r = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND"));
        if (!r.getSalonId().equals(salonId)) {
            // Same 404 as a missing row: an id must not be probeable for existence.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND");
        }
        /*
         * The mirror of the guard in withdraw(). A salon must not be able to "withdraw" a request
         * a STYLIST sent them — that is a decline, it owes the stylist a reason, and dressing it
         * up as a withdrawal would let an owner clear their inbox while leaving every applicant
         * believing they simply changed their mind.
         */
        if (!r.isInvitation()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "USE_DECLINE: they asked you, so this is yours to decline with a reason — not "
                    + "to withdraw.");
        }
        r.withdraw();   // throws if already answered; the entity refuses to re-decide
        log.info("Salon {} withdrew its invitation {} to stylist {}", salonId, requestId, r.getStylistId());
        return requests.save(r);
    }

    /**
     * The owner decides — and an acceptance is what creates the link.
     *
     * <h2>Why the link is created here and nowhere else</h2>
     * This is the single point where a stylist becomes part of a salon by their own initiative.
     * Keeping it in one transaction with the decision means there is no window in which a request
     * says "accepted" while the stylist is not actually on the team — a state that would look
     * fine to both parties and produce a stylist who cannot be booked.
     *
     * @param salonId taken from the caller's token by the controller, never from the body — so an
     *                owner can only ever decide requests addressed to their own salon
     */
    @Transactional
    public StylistJoinRequest decide(UUID salonId, UUID requestId, boolean accept,
                                      UUID deciderUserId, String note) {
        StylistJoinRequest r = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));

        // Same shape as the closure-cancel check: the path is authorised for THIS salon, and the
        // id in the body must be checked against it rather than trusted.
        if (!r.getSalonId().equals(salonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND");
        }

        /*
         * ══════════════════════════════════════════════════════════════════════════════════════
         * A SALON MAY NOT ACCEPT ITS OWN INVITATION. Session 65, and this is a bug fix on V028.
         * ══════════════════════════════════════════════════════════════════════════════════════
         * V028 made this table bidirectional, and this method was left matching on salon id
         * alone. That is now wrong in the worst available way: an owner's own outgoing invitation
         * lands in the very list this endpoint serves, and pressing "Add to my team" on it would
         * run the accept branch below and CREATE THE LINK — putting a stylist on a salon's roster
         * with no agreement from the stylist at all.
         *
         * That would not merely be a bug; it would delete the entire point of the invitation
         * feature. The whole difference between an invitation and a code is that the stylist
         * answers, and a salon that can answer for them has just gained the power to conscript
         * anybody whose phone number it knows.
         *
         * This is the same lesson this codebase keeps relearning, in a new place: DIRECTION IS
         * THE AUTHORISATION. Whoever did NOT propose the agreement is the one entitled to answer
         * it. `respondToInvitation` already enforced the mirror image of this rule — it refuses to
         * let a stylist "accept" the request they themselves sent — and the check has to exist on
         * both sides or the looser side is the one that runs.
         */
        if (r.isInvitation()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "NOT_YOURS_TO_DECIDE: you invited them, so it's theirs to accept. You can "
                    + "cancel the invitation instead.");
        }

        return applyDecision(salonId, r, accept, deciderUserId, note);
    }

    /**
     * The decision itself, once somebody has been found entitled to make it.
     *
     * <h2>Why this is separate from {@link #decide}</h2>
     * Two parties can answer a row in this table and WHICH ONE depends on the direction: a
     * stylist's request is the owner's to answer, an owner's invitation is the stylist's. The
     * checks that establish entitlement are therefore different, and they live in the two public
     * methods that own those doors.
     *
     * <p>What happens AFTERWARDS is identical — suspension re-check, one-active-salon re-check,
     * reuse the old link so a returning stylist keeps their salon rating, notify. Writing that
     * twice is how the two halves of one rule drift apart, which this session has now found in
     * four separate places. So it is written once, here, and neither caller can reach it without
     * having proved entitlement first.
     *
     * <p>PRIVATE on purpose: this method performs no authorisation of any kind, and a public
     * method that accepts a request without checking who is asking is a hole waiting for its
     * first caller.
     */
    private StylistJoinRequest applyDecision(UUID salonId, StylistJoinRequest r, boolean accept,
                                              UUID deciderUserId, String note) {
        if (!r.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ALREADY_DECIDED: this request was already " + r.getStatus() + ".");
        }
        if (!accept && (note == null || note.isBlank())) {
            // Same rule as a salon rejection. "No" with no reason produces a support ticket every
            // time, and the stylist has no idea whether to ask again.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "NOTE_REQUIRED: tell them why, even briefly — they'll otherwise just ask again.");
        }

        r.decide(accept, deciderUserId, note);
        requests.save(r);

        if (accept) {
            /*
             * Re-check INSIDE the accept, not only at request time. Requests sit here for days, and
             * in that window the stylist may have been invited through the old flow, or joined
             * somewhere else entirely.
             */
            // V025 — barred from the platform. Checked at ACCEPT, not at request time: a stylist
            // suspended after asking must not slip through on an owner's later click.
            suspensions.assertNotSuspended(r.getStylistId(), "join request accepted");

            Optional<StylistSalon> current = activeLink(r.getStylistId());
            if (current.isPresent() && !current.get().getSalonId().equals(salonId)) {
                /*
                 * They now work somewhere else. Refusing is the only correct answer: silently
                 * moving them would take a stylist off another salon's team without that owner
                 * knowing, and accepting anyway would violate V021 and fail on the insert with a
                 * constraint error nobody can read.
                 *
                 * The request stays PENDING — this transaction rolls back — so the owner can
                 * accept later if the stylist does leave.
                 */
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "STYLIST_AT_ANOTHER_SALON: they've joined another salon since asking. "
                        + "They'll need to leave there before you can add them.");
            }

            // Re-linking somebody who used to work here: reuse the OLD row rather than inserting a
            // second one, so their salon-scoped rating and review count survive coming back. A new
            // row would silently reset both to zero on their first day back.
            Optional<StylistSalon> past = links.findBySalonIdAndStylistId(salonId, r.getStylistId());
            if (current.isPresent()) {
                // Already active here — nothing to create. Not an error: the request is still
                // legitimately "accepted".
                log.info("Stylist {} was already active at salon {}; accepting request {} without "
                        + "creating a second link.", r.getStylistId(), salonId, r.getId());
            } else if (past.isPresent()) {
                StylistSalon back = past.get();
                back.setStatus(StylistSalon.ACTIVE);
                back.setLeftAt(null);
                back.setIsAvailableToday(true);
                links.save(back);
                log.info("Stylist {} REJOINED salon {} — previous link reactivated, salon rating "
                        + "and review count preserved.", r.getStylistId(), salonId);
            } else {
                links.save(new StylistSalon(r.getStylistId(), salonId, StylistSalon.ACTIVE,
                        // Salon-scoped rating starts empty, exactly like a newly invited stylist:
                        // their reputation at THIS salon has not been earned yet. Their OWN rating
                        // on the stylist row is untouched and travels with them.
                        null, 0, true, Instant.now(), null));
            }
            log.info("Salon {} accepted stylist {} (request {}, {})", salonId, r.getStylistId(), r.getId(), r.getDirection());
        } else {
            log.info("Salon {} declined stylist {} (request {}, {})", salonId, r.getStylistId(), r.getId(), r.getDirection());
        }

        publishDecision(r, accept);
        return r;
    }

    /**
     * Tell the stylist what the salon said. Session 49.
     *
     * <h2>The silence this fills</h2>
     * Session 48 built the whole ask-to-join flow and then told the stylist nothing. The decision
     * landed in a list they had to remember to open — and somebody who has asked and heard
     * nothing cannot tell "not looked at yet" from "declined" from "the app is broken". All three
     * lead to asking again.
     *
     * <h2>Non-fatal, deliberately, and loud</h2>
     * The decision is already saved and is the thing that matters — a stylist who is on the team
     * but wasn't emailed will find out the moment they open the app, whereas rolling the decision
     * back because an email failed would be absurd. But it is logged at ERROR, because from the
     * stylist's side a missing email is indistinguishable from having been ignored.
     */
    private void publishDecision(StylistJoinRequest r, boolean accepted) {
        String email = null;
        String name = null;
        try {
            Stylist s = stylists.findById(r.getStylistId()).orElse(null);
            if (s != null) {
                name = s.getName();
                if (s.getUserId() != null) {
                    var user = users.getUserById(s.getUserId()).getBody();
                    if (user != null) {
                        email = user.email();
                        // The stylist's PROFESSIONAL name is what they chose to be called here,
                        // so it wins over the account name if both exist.
                        if (name == null || name.isBlank()) name = user.name();
                    }
                }
            }
        } catch (Exception e) {
            // Publish anyway — the dispatcher logs "no contact details", which is recoverable and
            // visible. Dropping the event would leave no trace anyone was ever owed this.
            log.warn("Could not resolve stylist {} for the join-decision email ({}). Publishing "
                    + "without contact details.", r.getStylistId(), e.toString());
        }

        String salonName = salons.findById(r.getSalonId()).map(Salon::getName).orElse("the salon");

        try {
            outbox.publish(new com.bmp.common.events.StylistJoinRequestDecided(
                    r.getId(), r.getStylistId(), r.getSalonId(), salonName,
                    accepted, r.getDecisionNote(), email, name));
        } catch (Exception e) {
            log.error("Join request {} was decided ({}) but the stylist could not be told ({}). "
                    + "The decision itself is saved — they will see it in the app.",
                    r.getId(), accepted ? "accepted" : "declined", e.toString());
        }
    }

    /** Null-safe truncate. Free text from a form must not blow a column length. */
    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
