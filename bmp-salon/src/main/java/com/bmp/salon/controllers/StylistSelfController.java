package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.dto.StylistDtos;
import com.bmp.salon.entities.Stylist;
import com.bmp.salon.entities.StylistJoinRequest;
import com.bmp.salon.services.StylistSelfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stylist's own account: register, edit your profile, ask to join a salon. Session 48.
 *
 * <h2>Everything here is scoped to the CALLER</h2>
 * No endpoint takes a stylist id. The stylist is resolved from the token every time, so there is
 * no id in a path or a body that could be swapped for somebody else's — the shape of hole this
 * codebase has found repeatedly ("authorise the path, then trust the body") is absent because
 * there is no body to trust.
 *
 * <h2>Why registration is open to any authenticated user</h2>
 * {@code isAuthenticated()} rather than a role: the whole point is that a CUSTOMER presses "I'm a
 * stylist". Requiring the stylist role to become a stylist would be a door that can only be opened
 * from inside.
 *
 * <p>It grants no access to anything. A registered stylist with no salon can edit their own
 * profile and ask to join — they cannot see a booking, a customer or a calendar until an owner
 * accepts them.
 *
 * <h2>Why these live under /stylist-profile and not /stylists/me</h2>
 * {@code /api/v1/stylists/*} is in bmp-salon's PUBLIC paths — it serves a stylist's page to
 * customers browsing a salon. In Ant a single {@code *} matches exactly one segment, so
 * {@code /api/v1/stylists/me} would have matched it and had the JWT filter skipped entirely:
 * every endpoint here would have run with no authenticated principal, and
 * {@code caller.userId()} would have thrown on a null.
 *
 * <p>Caught by running the guest-path matcher over the new routes before writing the frontend.
 * It is the same collision that took down the public salon page earlier this session, arriving
 * from the opposite direction — there a private path was accidentally public, here a private
 * path would have been swallowed by a public pattern. A DIFFERENT ROOT IS THE FIX, not a
 * narrower wildcard: narrowing it would break the customer-facing stylist page it exists for.
 */
@Tag(name = "Stylist (self)")
@RestController
public class StylistSelfController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StylistSelfController.class);

    private final StylistSelfService service;

    public StylistSelfController(StylistSelfService service) {
        this.service = service;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────────────────────

    public record CreateStylistProfileRequest(
            @Size(max = 120) String name,
            /** "Balayage, curly cuts". Free text — the taxonomy is not settled and a fixed list
             *  would make every stylist pick the nearest wrong option. */
            @Size(max = 120) String speciality) {}

    public record UpdateStylistProfileRequest(
            @Size(max = 120) String name,
            @Size(max = 120) String speciality) {}

    public record JoinRequestBody(
            @NotNull UUID salonId,
            @Size(max = 500) String message) {}

    public record DecisionBody(
            boolean accept,
            @Size(max = 500) String note) {}

    /** Optional. Logged only — "why did my stylist vanish?" is the owner's first support ticket. */
    public record LeaveBody(@Size(max = 500) String reason) {}

    public record AvailableTodayBody(boolean isAvailableToday) {}

    /** The outcome of leaving or being removed: which salon, and that it is now in the past. */
    public record WorkHistoryEntryLite(UUID salonId, String status, Instant leftAt) {}

    /**
     * @param salonCount how many salons they actually work at. Zero is the normal state straight
     *                   after registering, and the UI must say so rather than looking broken.
     */
    public record StylistProfileResponse(
            UUID id, UUID userId, String name, String speciality,
            java.math.BigDecimal overallRating, int totalReviews, int salonCount,
            /**
             * Session 51 — the stylist's OWN view of a suspension.
             *
             * <h3>Why this had to be exposed here</h3>
             * Suspension emails them and stops them being bookable. Without this, somebody who
             * missed or lost that email opens the app to an empty calendar, an availability
             * toggle that appears to work, and nothing anywhere saying why. They would rationally
             * conclude the app is broken and contact the salon, who also cannot see the reason.
             *
             * <p>The reason is shown to THEM in full — unlike the message a salon gets when they
             * try to add a suspended stylist, which says only "unavailable, contact support",
             * because that reason may reference a safety complaint and the salon is a third party.
             */
            boolean suspended, String suspensionReason) {}

    /**
     * @param direction  V028 — {@code salon_to_stylist} means the SALON proposed it and the
     *                   stylist answers; {@code stylist_to_salon} is the original direction. The
     *                   client needs it to decide whether to render "Accept / Decline" or
     *                   "Waiting for them" against the same row.
     * @param salonName  resolved here so an inbox does not show a UUID. A stylist deciding whether
     *                   to join needs the name of the place, which is the entire decision.
     */
    public record JoinRequestResponse(
            UUID id, UUID stylistId, UUID salonId, String salonName, String status,
            String direction, String message, String decisionNote,
            Instant decidedAt, Instant createdAt) {}

    // ── profile ──────────────────────────────────────────────────────────────────────────────

    @Operation(
        summary = "Register as a stylist",
        description = "Any signed-in user. Creates the stylist profile and adds the stylist role — the customer role is KEPT, because a stylist books haircuts too. Grants no access to any salon: that needs an owner to accept a join request.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/api/v1/stylist-profile")
    @ResponseStatus(HttpStatus.CREATED)
    public StylistProfileResponse register(@Valid @RequestBody CreateStylistProfileRequest req,
                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        return toProfile(service.createProfile(caller.userId(), req.name(), req.speciality()),
                caller.userId());
    }

    @Operation(summary = "My stylist profile", description = "404 if this account has not registered as a stylist.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/v1/stylist-profile")
    public StylistProfileResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return toProfile(service.myProfile(caller.userId()), caller.userId());
    }

    @Operation(
        summary = "Update my stylist profile",
        description = "Editable with no salon attached — the profile belongs to the stylist, not to whichever salon happens to employ them.")
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/api/v1/stylist-profile")
    public StylistProfileResponse update(@Valid @RequestBody UpdateStylistProfileRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser caller) {
        return toProfile(service.updateProfile(caller.userId(), req.name(), req.speciality()),
                caller.userId());
    }

    // ── where I work, and where I used to ────────────────────────────────────────────────────

    @Operation(
        summary = "My work history",
        description = "Every salon I've worked at, current first. Past salons are KEPT when I "
            + "leave — that record is the point, and it is what makes moving salons possible "
            + "without starting over.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/v1/stylist-profile/salons")
    public List<StylistSelfService.WorkHistoryEntry> myWorkHistory(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.myWorkHistory(caller.userId());
    }

    @Operation(
        summary = "Am I taking bookings today?",
        description = "The stylist's own fastest lever. Stops NEW bookings immediately; anything "
            + "already booked stands, because those are commitments to real customers. Scoped "
            + "from the token — the salon-scoped version of this endpoint can never pass its own "
            + "guard for a stylist, whose JWT has no salonId.")
    @PreAuthorize("hasRole('STYLIST')")
    @PutMapping("/api/v1/stylist-profile/available-today")
    public AvailableTodayBody setAvailableToday(@RequestBody AvailableTodayBody body,
                                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        var link = service.setMyAvailabilityToday(caller.userId(), body.isAvailableToday());
        return new AvailableTodayBody(link.isAvailableToday());
    }

    @Operation(summary = "Am I currently taking bookings?", description = "False when not on a team.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/api/v1/stylist-profile/available-today")
    public AvailableTodayBody availableToday(@AuthenticationPrincipal AuthenticatedUser caller) {
        return new AvailableTodayBody(
                service.activeLink(service.myProfile(caller.userId()).getId())
                        .map(com.bmp.salon.entities.StylistSalon::isAvailableToday)
                        .orElse(false));
    }

    /**
     * The stylist resigns.
     *
     * <h2>Why a stylist can do this alone</h2>
     * The alternative is that leaving needs the owner to press a button — and an owner who is
     * annoyed, busy or gone never presses it. The stylist is then stuck: shown on a salon's public
     * page they no longer work at, and unable to join anywhere else now that one-salon-at-a-time
     * is enforced. Employment is not a lock.
     *
     * <p>They cannot erase it. The link becomes {@code alumni} with a {@code left_at}; the salon
     * keeps the record and so do they.
     */
    @Operation(
        summary = "Leave my current salon",
        description = "Ends the employment; keeps the history. My profile, rating and reviews are "
            + "untouched, and I can ask to join somewhere else straight away.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/api/v1/stylist-profile/resign")
    public WorkHistoryEntryLite leave(@RequestBody(required = false) LeaveBody body,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        var link = service.leaveCurrentSalon(caller.userId(), body == null ? null : body.reason());
        return new WorkHistoryEntryLite(link.getSalonId(), link.getStatus(), link.getLeftAt());
    }

    /*
     * The OWNER's side of leaving already exists and is NOT duplicated here:
     *
     *     POST /api/v1/salons/{salonId}/stylists/{stylistId}/alumni   (StylistController)
     *
     * It predates this class. A second endpoint doing the same thing is how two paths drift into
     * two different meanings of "removed" — one freezing the rating snapshot and one not.
     * StylistCrudService.markAlumni now routes through the same StylistSalon.leave() this class
     * uses, so both directions end employment identically.
     */

    // ── join requests, stylist side ──────────────────────────────────────────────────────────

    @Operation(
        summary = "Ask a salon to add me",
        description = "Creates a request the salon's owner can accept or decline. Being accepted is the ONLY thing that puts a stylist on a salon's team — nobody can add themselves.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/api/v1/stylist-profile/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinRequestResponse requestToJoin(@Valid @RequestBody JoinRequestBody req,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return toRequest(service.requestToJoin(caller.userId(), req.salonId(), req.message()));
    }

    @Operation(summary = "Where I've asked to work", description = "Newest first, including decided ones.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/v1/stylist-profile/join-requests")
    public List<JoinRequestResponse> myRequests(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.myRequests(caller.userId()).stream().map(this::toRequest).toList();
    }

    @Operation(summary = "Withdraw a request I sent", description = "Only while it is still pending. Recorded as withdrawn, not declined — the salon did not refuse.")
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/api/v1/stylist-profile/join-requests/{requestId}")
    public JoinRequestResponse withdraw(@PathVariable UUID requestId,
                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        return toRequest(service.withdraw(caller.userId(), requestId));
    }

    // ── join requests, owner side ────────────────────────────────────────────────────────────

    @Operation(
        summary = "Stylists asking to join my salon",
        description = "Oldest first — somebody has been waiting longest. Owner or manager: adding a stylist to the floor is day-to-day work.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @GetMapping("/api/v1/salons/{salonId}/join-requests")
    public List<JoinRequestResponse> pending(@PathVariable UUID salonId,
                                              @RequestParam(defaultValue = "true") boolean pendingOnly) {
        var rows = pendingOnly ? service.pendingFor(salonId) : service.allFor(salonId);
        return rows.stream().map(this::toRequest).toList();
    }

    @Operation(
        summary = "Accept or decline a stylist",
        description = "Accepting adds them to the team in the same transaction — there is no window where the request says accepted but the stylist cannot be booked. A decline REQUIRES a reason: 'no' with no explanation just produces the same request again next week.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/join-requests/{requestId}/decide")
    public JoinRequestResponse decide(@PathVariable UUID salonId,
                                       @PathVariable UUID requestId,
                                       @Valid @RequestBody DecisionBody body,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        return toRequest(service.decide(salonId, requestId, body.accept(),
                caller == null ? null : caller.userId(), body.note()));
    }

    // ── V028: the salon invites a stylist who already has an account ─────────────────────────

    public record InviteCandidateResponse(UUID stylistId, String name, String speciality,
                                           String phoneMasked, boolean alreadyOnATeam,
                                           String currentSalonName, boolean suspended,
                                           boolean canInvite, String why) {}

    public record InviteBody(@Size(max = 500) String message) {}

    @Operation(
        summary = "Find a stylist to invite, by phone or email",
        description = """
            For a stylist who ALREADY has a BMP account. Returns one match or none.

            'None' is a normal answer, not an error: it means nobody with that phone or email has             a stylist profile, so invite them by code instead — the code path is what exists for             people who have never used BMP.

            A stylist already on another salon's team is RETURNED, with canInvite false and the             reason. Hiding them would leave an owner thinking the search is broken; telling them             'she's at Studio Nine' explains why and what to do.""")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @GetMapping("/api/v1/salons/{salonId}/stylist-search")
    public InviteCandidateResponse searchStylist(@PathVariable UUID salonId,
                                                  @RequestParam String q) {
        var found = service.findInviteCandidate(q);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "NO_STYLIST_ACCOUNT: nobody with that phone or email has a stylist profile on "
                    + "BMP yet. Invite them with a code instead — they'll join when they sign up.");
        }
        var c = found.get();
        String why = c.suspended()
                ? "This stylist is suspended from BMP and can't join a salon."
                : c.alreadyOnATeam()
                    ? "Already on " + (c.currentSalonName() == null ? "another salon's" : c.currentSalonName() + "'s")
                      + " team. They can only be on one at a time — they'd need to leave there first."
                    : null;
        return new InviteCandidateResponse(c.stylistId(), c.name(), c.speciality(), c.phoneMasked(),
                c.alreadyOnATeam(), c.currentSalonName(), c.suspended(), why == null, why);
    }

    @Operation(
        summary = "Invite a stylist onto my team",
        description = "They get it in their app and decide. Unlike a code invite, nothing is shared out of band and nothing happens until they accept — which is the point: a salon cannot add somebody to its team without their agreement.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/invite")
    public JoinRequestResponse inviteStylist(@PathVariable UUID salonId,
                                              @PathVariable UUID stylistId,
                                              @Valid @RequestBody(required = false) InviteBody body,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return toRequest(service.inviteStylist(salonId, stylistId,
                body == null ? null : body.message(),
                caller == null ? null : caller.userId()));
    }

    /*
     * NOTE: GET /api/v1/stylist-profile/services — a stylist's own specialisations — lives in
     * StylistController, NOT here, despite the /stylist-profile path.
     *
     * The mapping it needs (StylistCrudService.toStylistServiceResponse, which joins each row to
     * the salon's service for its name and base price) lives in StylistCrudService, and that
     * class already injects THIS service as `lookups`. Injecting it back here would be a circular
     * bean dependency, which Spring refuses at startup.
     *
     * Duplicating the mapper instead would be worse: two copies of one join is the drift pattern
     * this codebase keeps finding. The URL is served by whichever controller holds the right
     * collaborator — Spring does not care which class a path lives on, and the path is what the
     * client sees.
     */

    @Operation(
        summary = "Take back an invitation we sent",
        description = """
            Before the stylist answers. Darshan: "even salon can revoke".

            This is not a decline — a decline answers somebody ELSE's request and owes them a             reason. This withdraws our own, and it exists because only one open conversation is             allowed per salon-stylist pair: inviting the wrong Ravi would otherwise lock the salon             out of inviting the right one until he got round to answering.""")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @DeleteMapping("/api/v1/salons/{salonId}/invitations/{requestId}")
    public JoinRequestResponse withdrawInvitation(@PathVariable UUID salonId,
                                                   @PathVariable UUID requestId) {
        return toRequest(service.withdrawInvitation(salonId, requestId));
    }

    @Operation(
        summary = "Invitations waiting on me",
        description = "Salons that have asked ME to join. Distinct from the requests I sent, which are waiting on somebody else — the same table holds both, and only these are mine to answer.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/v1/stylist-profile/invitations")
    public List<JoinRequestResponse> myInvitations(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.myInvitations(caller.userId()).stream().map(this::toRequest).toList();
    }

    @Operation(
        summary = "Accept or decline an invitation",
        description = "Accepting puts me on their team in the same transaction. Declining needs a brief reason, the same rule an owner follows — 'no' with nothing attached just gets asked again next week.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/api/v1/stylist-profile/invitations/{requestId}/respond")
    public JoinRequestResponse respondToInvitation(@PathVariable UUID requestId,
                                                    @Valid @RequestBody DecisionBody body,
                                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        return toRequest(service.respondToInvitation(
                caller.userId(), requestId, body.accept(), body.note()));
    }

    // ── mapping ──────────────────────────────────────────────────────────────────────────────

    private StylistProfileResponse toProfile(Stylist s, UUID userId) {
        // Counts only ACTIVE links: a stylist who has left a salon is not on its team, and showing
        // them as attached would tell them they can take bookings there.
        int active = (int) service.myLinks(userId).stream()
                .filter(com.bmp.salon.entities.StylistSalon::isActive)
                .count();
        return new StylistProfileResponse(s.getId(), s.getUserId(), s.getName(), s.getSpeciality(),
                s.getOverallRating(), s.getTotalReviews(), active,
                s.isSuspended(), s.isSuspended() ? s.getSuspensionReason() : null);
    }

    private JoinRequestResponse toRequest(StylistJoinRequest r) {
        /*
         * The salon NAME is resolved here rather than left to the client. A stylist's inbox
         * showing "You have been invited by 3f9c-…" is not an invitation anybody can act on — the
         * name of the place is the whole decision.
         *
         * Degrades to null rather than failing the row: a salon that cannot be read is a
         * different problem from an invitation that cannot be listed.
         */
        String name = null;
        try {
            name = service.salonName(r.getSalonId());
        } catch (Exception e) {
            log.warn("Could not resolve the salon name for join request {} ({})", r.getId(), e.toString());
        }
        return new JoinRequestResponse(r.getId(), r.getStylistId(), r.getSalonId(), name,
                r.getStatus(), r.getDirection(), r.getMessage(), r.getDecisionNote(),
                r.getDecidedAt(), r.getCreatedAt());
    }
}
