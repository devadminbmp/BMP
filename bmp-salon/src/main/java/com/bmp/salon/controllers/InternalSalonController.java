package com.bmp.salon.controllers;

import com.bmp.salon.entities.Salon;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.SalonRepository;
import com.bmp.salon.repositories.StylistAvailabilityRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal salon endpoints for the staff console — {@code ROLE_SERVICE} only, called by
 * bmp-admin.
 *
 * <p>Two jobs, both of which the console cannot do any other way:
 *
 * <ol>
 *   <li><b>Flip a salon's visibility</b> when a moderator approves, rejects or suspends it.
 *       Without this the console's approval button changes a row in admin_schema and nothing
 *       else — the salon stays invisible and the moderator has no idea.</li>
 *   <li><b>Answer "why aren't we getting bookings?"</b>, which is the most common salon support
 *       call and almost always a configuration problem rather than a demand problem.</li>
 * </ol>
 */
@Tag(name = "Internal salon (staff console)", description = "Service-to-service: apply a moderation decision, and diagnose a salon that isn't receiving bookings.")
@RestController
@RequestMapping("/api/v1/salons/internal")
@PreAuthorize("hasRole('SERVICE')")
public class InternalSalonController {

    private static final Logger log = LoggerFactory.getLogger(InternalSalonController.class);

    private final SalonRepository salons;
    private final StylistSalonRepository stylistSalons;
    private final StylistAvailabilityRepository availability;
    /** Session 48 — resolving a userId to a stylist profile for bmp-booking. */
    private final com.bmp.salon.repositories.StylistRepository stylistProfiles;
    /** V025 (Session 51) — suspension and reinstatement, driven by the admin console. */
    private final com.bmp.salon.services.StylistAdminService stylistAdmin;
    /** For admin-initiated removal from one salon — the SAME markAlumni the owner calls. */
    private final com.bmp.salon.services.StylistCrudService stylistCrud;

    // Session 46 — telling the owner their salon was approved or rejected. See setStatus.
    private final com.bmp.salon.repositories.SalonStaffRepository staff;
    private final com.bmp.salon.client.UserServiceClient users;
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    /** For the commission agreed at approval — see setStatus. Session 48. */
    private final com.bmp.salon.services.SalonService salonService;

    public InternalSalonController(SalonRepository salons, StylistSalonRepository stylistSalons,
                                    StylistAvailabilityRepository availability,
                                    com.bmp.salon.repositories.SalonStaffRepository staff,
                                    com.bmp.salon.client.UserServiceClient users,
                                    com.bmp.common.outbox.OutboxPublisher outbox,
                                    com.bmp.salon.services.SalonService salonService,
                                    com.bmp.salon.repositories.StylistRepository stylistProfiles,
                                    com.bmp.salon.services.StylistAdminService stylistAdmin,
                                    com.bmp.salon.services.StylistCrudService stylistCrud) {
        this.stylistAdmin = stylistAdmin;
        this.stylistCrud = stylistCrud;
        this.salons = salons;
        this.stylistSalons = stylistSalons;
        this.availability = availability;
        this.stylistProfiles = stylistProfiles;
        this.staff = staff;
        this.users = users;
        this.outbox = outbox;
        this.salonService = salonService;
    }

    /**
     * "Which stylist is this login, and where do they work?" Session 48.
     *
     * <h2>Why bmp-booking needs this</h2>
     * A stylist's JWT carries {@code userId} and the role, but <b>no salonId</b> — deliberately,
     * because a stylist is a portable profile rather than a salon-scoped seat (see StaffService).
     * bmp-booking has to answer "show me MY day", and to do that it needs the {@code stylist.id}
     * and the salon they currently work at. Neither is in the token and neither can be trusted
     * from the request body, or one stylist could read another's schedule by changing an id.
     *
     * <p>So the resolution happens here, service-to-service, from the userId in the caller's
     * token. That is the whole security property of the stylist schedule endpoints: the id is
     * derived, never supplied.
     *
     * @param salonId null when they are not on any salon's team right now — a real state after
     *                self-registration, and the caller must treat it as "no schedule", not an error
     */
    public record StylistIdentity(UUID stylistId, UUID salonId, String name) {}

    @io.swagger.v3.oas.annotations.Operation(
        summary = "Resolve a user id to their stylist profile and current salon",
        description = "SERVICE only. Used by bmp-booking so a stylist's own schedule can be scoped "
            + "from their token rather than from an id they send. 404 if this user has no stylist "
            + "profile; salonId is null if they're not currently on a team.")
    @GetMapping("/stylist-by-user/{userId}")
    public StylistIdentity stylistByUser(@PathVariable UUID userId) {
        var stylist = stylistProfiles.findFirstByUserId(userId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "NO_STYLIST_PROFILE: this user has not registered as a stylist."));

        // The ONE active link, or null. V021 guarantees there is at most one; alumni links are
        // history and must not resolve to a salon whose schedule they could then read.
        UUID salonId = stylistSalons.findByStylistId(stylist.getId()).stream()
                .filter(com.bmp.salon.entities.StylistSalon::isActive)
                .map(com.bmp.salon.entities.StylistSalon::getSalonId)
                .findFirst()
                .orElse(null);

        return new StylistIdentity(stylist.getId(), salonId, stylist.getName());
    }

    // ══ platform power over a stylist. V025 (Session 51). ═════════════════════════════════════
    //
    // Called by bmp-admin's console. SERVICE-guarded like everything else in this class, so the
    // authorisation that matters — which admin role may do this — lives in bmp-admin behind its
    // own signing key, and is audited there.

    public record SuspendStylistRequest(
            /** Required, min 5 chars. Shown to the stylist: a bar they can't contest isn't a
             *  decision, it's a wall. Enforced again by V025's CHECK. */
            @Size(max = 500) String reason,
            /** The bmp_staff id. Every admin action here is attributable. */
            UUID staffId) {}

    /**
     * @param activeSalonCount how many teams they are on right now. The console shows this before
     *                         asking for confirmation — suspending somebody mid-employment is a
     *                         different decision from suspending somebody with no current salon.
     */
    public record StylistAdminView(
            UUID id, UUID userId, String name, String speciality,
            java.math.BigDecimal overallRating, int totalReviews,
            boolean suspended, Instant suspendedAt, String suspensionReason,
            UUID suspendedByStaffId, Instant reinstatedAt,
            int activeSalonCount, List<UUID> activeSalonIds) {}

    /**
     * Just enough to send a stylist a message. Session 53.
     *
     * <p>Separate from {@code /stylists/{id}} deliberately: that returns the admin console's view,
     * including suspension reasons and every salon they are on. bmp-booking calls this once per
     * assigned stylist per booking, and handing a booking service somebody's moderation history to
     * find an email address is more than it needs and more than it should hold.
     *
     * @param userId null if the stylist profile was created by a salon invite and never claimed —
     *               there is then no account and no address, which the caller must treat as
     *               "cannot tell them", not as an error.
     */
    public record StylistContact(UUID stylistId, UUID userId, String name) {}

    @io.swagger.v3.oas.annotations.Operation(
        summary = "A stylist's name and the account behind it",
        description = "SERVICE only. The minimum needed to notify a stylist. 404 if no such stylist; userId is null for an unclaimed invite-created profile.")
    @GetMapping("/stylist-contact/{stylistId}")
    public StylistContact stylistContact(@PathVariable UUID stylistId) {
        var stylist = stylistProfiles.findById(stylistId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "NO_SUCH_STYLIST"));
        return new StylistContact(stylist.getId(), stylist.getUserId(), stylist.getName());
    }

    @io.swagger.v3.oas.annotations.Operation(
        summary = "One stylist, for the admin console",
        description = "Includes their suspension state and which salons they're currently on.")
    @GetMapping("/stylists/{stylistId}")
    public StylistAdminView stylist(@PathVariable UUID stylistId) {
        return toAdminView(stylistAdmin.get(stylistId));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Every currently-suspended stylist")
    @GetMapping("/stylists/suspended")
    public List<StylistAdminView> suspendedStylists() {
        return stylistAdmin.suspended().stream().map(this::toAdminView).toList();
    }

    /**
     * Bar a stylist from the platform.
     *
     * <h2>Existing salon links are NOT touched</h2>
     * Deliberately. Flipping every active link to alumni would destroy a salon's honest record of
     * an employment that really happened, and reinstating could not restore it. The link stays;
     * the stylist simply stops being bookable, which {@code AvailabilityService} enforces.
     */
    @io.swagger.v3.oas.annotations.Operation(
        summary = "Suspend a stylist from BMP entirely",
        description = "They cannot be added to any salon, cannot be accepted from a join request, "
            + "and produce no bookable slots anywhere. Existing team memberships are left intact "
            + "— this is not a deletion of anyone's history. Reversible.")
    @PostMapping("/stylists/{stylistId}/suspend")
    public StylistAdminView suspendStylist(@PathVariable UUID stylistId,
                                            @Valid @RequestBody SuspendStylistRequest req) {
        return toAdminView(stylistAdmin.suspend(stylistId, req.reason(), req.staffId()));
    }

    @io.swagger.v3.oas.annotations.Operation(
        summary = "Lift a suspension",
        description = "Bookable again wherever they are still on a team. Salons that removed them "
            + "in the meantime must re-add them — reinstating does not undo a salon's own decision.")
    @PostMapping("/stylists/{stylistId}/reinstate")
    public StylistAdminView reinstateStylist(@PathVariable UUID stylistId) {
        return toAdminView(stylistAdmin.reinstate(stylistId));
    }

    /**
     * Admin removes a stylist from one salon's team.
     *
     * <p>The same act the owner performs, for when a salon can't or won't — a salon that has gone
     * quiet, or one whose owner is the subject of the complaint. Routed through the same
     * {@code markAlumni} so "removed" means exactly one thing however it happened.
     */
    @io.swagger.v3.oas.annotations.Operation(
        summary = "Remove a stylist from one salon's team (admin)",
        description = "Same effect as the owner's action: the link becomes alumni and the history "
            + "is kept. Does NOT bar them from BMP — use suspend for that.")
    @PostMapping("/salons/{salonId}/stylists/{stylistId}/remove")
    public void adminRemoveFromSalon(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                      @RequestParam(required = false) UUID staffId) {
        stylistCrud.markAlumni(salonId, stylistId, staffId, "admin");
    }

    private StylistAdminView toAdminView(com.bmp.salon.entities.Stylist s) {
        var active = stylistAdmin.activeLinks(s.getId());
        return new StylistAdminView(
                s.getId(), s.getUserId(), s.getName(), s.getSpeciality(),
                s.getOverallRating(), s.getTotalReviews(),
                s.isSuspended(), s.getSuspendedAt(), s.getSuspensionReason(),
                s.getSuspendedByStaffId(), s.getReinstatedAt(),
                active.size(),
                active.stream().map(com.bmp.salon.entities.StylistSalon::getSalonId).toList());
    }

    public record StatusChangeRequest(
        /*
         * 'deleted' added Session 48 — a SOFT delete.
         *
         * The row is kept and simply stops being visible or bookable. Hard-deleting is not an
         * option worth building: bookings, reviews, payouts and audit rows all reference this
         * salon by id, and removing it would orphan a customer's booking history and the money
         * trail attached to it. "Remove it from the site" is what an admin means; "destroy the
         * evidence that it ever traded" is not.
         *
         * Reversible by setting a status back — deliberately, because the usual reason a salon is
         * deleted in a hurry is a mistake.
         */
        @NotBlank @Pattern(regexp = "^(pending|approved|active|rejected|suspended|deleted)$") String status,
        /**
         * Commission in basis points, agreed at approval. Session 48. Null = leave unchanged.
         *
         * <p>Bounds mirror V009's CHECK so a bad value is a 400 naming the problem rather than a
         * 500 from the database. 5000 catches the predictable mistake of typing "12" meaning 12%
         * into a basis-points field, which would silently mean 0.12%.
         *
         * <p>Reachable ONLY through this internal, service-key-guarded endpoint. The owner-facing
         * policy endpoint deliberately excludes commission — a salon must never set its own rate.
         */
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(5000)
        Integer commissionBps,
        /**
         * The moderator's reason. Session 46 — carried here so the OWNER can be told it.
         *
         * <p>bmp-admin already requires it (min 10 chars) on a rejection, because a refusal with
         * no reason produces a support ticket every single time. It travels with the decision so
         * the notification can quote it verbatim: the note IS the message on a rejection.
         *
         * <p>Nullable — approvals and suspensions may carry one but don't have to.
         */
        String decisionNote
    ) {}

    public record SalonSupportSummary(
        UUID id, String name, String location, String status,
        int stylistCount, int activeStylistsToday, int stylistsWithHours,
        /** The answer to "why aren't we getting bookings", or null if nothing is obviously wrong. */
        String configWarning
    ) {}

    // ══ Session 48: everything a moderator needs to actually judge a salon ═══════════════════

    /**
     * The full review packet for one salon.
     *
     * <h2>Why the console was showing almost nothing</h2>
     * bmp-admin's queue rendered a salon NAME and nothing else — ownerName and area were literally
     * passed as {@code null}, and its SalonDto carried only (id, name, location, strategy). A
     * moderator was being asked to approve a business they could see four fields of, one of which
     * was a UUID. The checklist they tick ("address matches the pin", "photos are of this salon")
     * referred to things the screen did not show them.
     *
     * <p>Everything here is READ-ONLY and internal (hasRole('SERVICE') on the class). It carries
     * the owner's real phone and email deliberately — verifying a salon means being able to ring
     * them, and a moderation queue where every check needs a second lookup is a queue that gets
     * approved without checking.
     */
    public record ModerationServiceItem(String name, long pricePaise, int durationMinutes, boolean archived) {}
    public record ModerationPhoto(String url, String caption) {}
    public record ModerationPacket(
        UUID salonId, String reference, String name, String status,
        String area, String pincode, String address, String about,
        String imageUrl, Double lat, Double lng, String mapLink,
        List<String> categories,
        UUID ownerUserId, String ownerName, String ownerEmail, String ownerPhone,
        int stylistCount,
        List<ModerationServiceItem> services,
        List<ModerationPhoto> photos,
        java.time.Instant createdAt) {}

    /**
     * Edit a salon's profile on behalf of console staff. Session 65.
     *
     * <h2>Why bmp-salon does not decide who may call this</h2>
     * It cannot: the caller is bmp-admin holding a ROLE_SERVICE key, and the human behind it is a
     * support agent or an ops admin whose role only bmp-admin can verify. WHICH FIELDS that person
     * may change is decided by {@code SalonEditScope} over there, next to the staff token, the
     * audit log and the reason they typed.
     *
     * <p>What bmp-salon still owns is the INVARIANT: {@code SalonService.update} rejects
     * {@code status} outright, so no caller — console, owner or otherwise — can approve a salon
     * through a profile edit. That check stays here because it protects the data, not the workflow.
     *
     * <h2>PATCH semantics, like the owner's own editor</h2>
     * Null means unchanged. A console that does not know about a field cannot blank it, which is
     * what makes it safe to add fields later without every caller being updated in lockstep.
     */
    @Operation(summary = "[internal] Edit a salon's profile for console staff",
               description = "Called by bmp-admin after its own per-field authority check. Null means unchanged. `status` is rejected — approval is moderation.")
    @PatchMapping("/{salonId}/profile")
    public com.bmp.salon.dto.SalonDtos.SalonResponse editProfile(
            @PathVariable UUID salonId,
            @RequestBody com.bmp.salon.dto.SalonDtos.UpdateSalonRequest req) {
        return salonService.update(salonId, req);
    }

    @Operation(
        summary = "Everything needed to review a salon",
        description = "Called by bmp-admin to render the moderation screen: the listing as a customer would see it, plus the owner's contact details so the reviewer can verify it.")
    @GetMapping("/{salonId}/moderation")
    public ModerationPacket moderationPacket(@PathVariable UUID salonId) {
        Salon s = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        // Owner: resolved through the staff table, then bmp-user for the contact details.
        // findBySalonId + filter, because there is no by-role finder and adding one for a single
        // internal read is more surface than a stream over a handful of staff rows.
        UUID ownerUserId = staff.findBySalonId(salonId).stream()
                .filter(x -> "owner".equalsIgnoreCase(x.getRole()))
                .findFirst().map(x -> x.getUserId()).orElse(null);
        String ownerName = null, ownerEmail = null, ownerPhone = null;
        if (ownerUserId != null) {
            try {
                var u = users.getUserById(ownerUserId).getBody();
                if (u != null) { ownerName = u.name(); ownerEmail = u.email(); ownerPhone = u.phone(); }
            } catch (Exception e) {
                // Degraded, not fatal — a reviewer with the listing but no phone number can still
                // work; one staring at an error page cannot.
                log.warn("Could not resolve owner {} of salon {} for the review packet ({})",
                        ownerUserId, salonId, e.toString());
            }
        }

        Double lat = null, lng = null;
        String mapLink = null;
        try {
            String[] parts = s.getLocation().split(",");
            lat = Double.parseDouble(parts[0].trim());
            lng = Double.parseDouble(parts[1].trim());
            // A link the reviewer can actually click. Checking the pin is on the right building is
            // the single most valuable thing they do — a wrong pin removes the salon from the
            // results of everyone nearby — and it is impossible from two decimal numbers.
            mapLink = "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng;
        } catch (Exception ignored) {
            // Malformed location: leave the fields null rather than guessing coordinates.
        }

        return new ModerationPacket(
                s.getId(), s.getReference(), s.getName(), s.getStatus(),
                s.getArea(), s.getPincode(), s.getAddress(), s.getAbout(),
                s.getImageUrl(), lat, lng, mapLink,
                salonService.categoriesOfSalon(salonId),
                ownerUserId, ownerName, ownerEmail, ownerPhone,
                stylistSalons.findBySalonIdAndStatus(salonId, "active").size(),
                salonService.servicesForModeration(salonId),
                salonService.photosForModeration(salonId),
                s.getCreatedAt());
    }

    /**
     * Salons sitting at 'pending'. Session 48.
     *
     * <h2>The bug this closes</h2>
     * bmp-admin's queue listed {@code salon_review} rows and nothing else. Creating a salon calls
     * bmp-admin to enqueue a review, and that call is deliberately best-effort — a moderation
     * queue being down must not block somebody's signup. But when it failed, the salon sat at
     * 'pending' with NO review row, which made it **invisible to the console forever**. The owner
     * waited for a decision that could not be made, and nobody knew.
     *
     * <p>bmp-admin now reconciles against this list every time the queue is opened, so a failed
     * enqueue self-heals instead of silently costing a salon.
     */
    /** One row of the console's salon list. Enough to find a salon and see what state it is in. */
    public record SalonAdminRow(
        UUID salonId, String reference, String name, String status,
        String area, String pincode, String ownerName, String ownerPhone,
        java.time.Instant createdAt, java.time.Instant wentLiveAt) {}

    @Operation(
        summary = "Every salon, for the console",
        description = "The admin's list of all salons regardless of status — the moderation queue only ever showed ones awaiting review, so a salon that was already live could not be found, let alone suspended.")
    @GetMapping("/all")
    public List<SalonAdminRow> allSalons(@RequestParam(required = false) String status) {
        List<Salon> rows = (status == null || status.isBlank())
                ? salons.findAll()
                : salons.findByStatus(status);
        return rows.stream()
                // Newest first: the salon an admin is looking for is nearly always a recent one.
                .sorted(java.util.Comparator.comparing(Salon::getCreatedAt).reversed())
                .map(s -> {
                    String ownerName = null, ownerPhone = null;
                    UUID ownerId = staff.findBySalonId(s.getId()).stream()
                            .filter(x -> "owner".equalsIgnoreCase(x.getRole()))
                            .findFirst().map(x -> x.getUserId()).orElse(null);
                    if (ownerId != null) {
                        try {
                            var u = users.getUserById(ownerId).getBody();
                            if (u != null) { ownerName = u.name(); ownerPhone = u.phone(); }
                        } catch (Exception ignored) {
                            // Degraded row beats a failed list — see moderationPacket.
                        }
                    }
                    return new SalonAdminRow(s.getId(), s.getReference(), s.getName(), s.getStatus(),
                            s.getArea(), s.getPincode(), ownerName, ownerPhone,
                            s.getCreatedAt(), s.getWentLiveAt());
                })
                .toList();
    }

    @Operation(summary = "Salons awaiting review",
               description = "Used by bmp-admin to find salons whose review row was never created — a failed enqueue would otherwise hide them from the queue permanently.")
    @GetMapping("/pending-review")
    public List<UUID> pendingReview() {
        return salons.findByStatus("pending").stream().map(Salon::getId).toList();
    }

    @Operation(
        summary = "Apply a moderation decision",
        description = "Called by bmp-admin when a moderator approves, rejects or suspends a salon. This is what actually makes a salon visible to customers — the console's own row is a record of the decision, not the switch.")
    @PutMapping("/{salonId}/status")
    @Transactional
    public ResponseEntity<Void> setStatus(@PathVariable UUID salonId,
                                           @RequestBody StatusChangeRequest req) {
        Salon salon = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        String previous = salon.getStatus();
        salon.setStatus(req.status());
        salon.touch();
        salons.save(salon);

        /*
         * The agreed commission, applied in the SAME transaction as the approval. Session 48.
         *
         * Order matters: the status write comes first so that if the rate is out of range and
         * this throws, @Transactional rolls BOTH back. A salon that went live at a rate nobody
         * agreed — because the status stuck and the rate didn't — is a money bug, and money bugs
         * found later are found in a payout dispute.
         *
         * No-op when null, which is every rejection and every approval where the reviewer left
         * the default alone. See StatusChangeRequest.
         */
        salonService.setCommissionBps(salonId, req.commissionBps());

        /*
         * Session 46 — tell the owner. This is published HERE rather than in bmp-admin, and the
         * reason is ownership of the aggregate:
         *
         *   · bmp-salon knows WHO the owner is (salon_staff) — bmp-admin's SalonDto has no owner
         *     field at all, so it literally cannot address the message.
         *   · the event is written to the outbox in the SAME transaction as the status change,
         *     so "the salon is approved" and "the owner was told" cannot disagree. Publishing
         *     from bmp-admin would leave a window where the salon goes live silently.
         *
         * Before this, decide() published nothing whatsoever. A moderator approved a salon and
         * the owner found out by opening the app and guessing; a rejection they found out never.
         * Approval is the single most anticipated moment in a partner's relationship with BMP,
         * and sending nothing turns it into a silence indistinguishable from being ignored.
         *
         * Skipped when the status hasn't actually changed — bmp-admin's decide() is not
         * idempotent-safe against retries, and a partner getting "you're approved!" twice reads
         * as a system that doesn't know what it's doing.
         */
        if (!req.status().equals(previous)) {
            publishStatusChange(salon, req.status(), req.decisionNote());
        }

        /*
         * A rejected salon's uploaded images have no remaining purpose. Session 48.
         *
         * AFTER the status write and the event, never before: the rejection is the thing that
         * matters and must not be held up — or undone — by object storage. purge... swallows its
         * own failures for the same reason.
         */
        if ("rejected".equals(req.status())) {
            salonService.purgeUploadedImagesOnRejection(salonId);
        }

        log.info("Salon {} status {} -> {} by the staff console", salonId, previous, req.status());
        return ResponseEntity.noContent().build();
    }

    /**
     * Emit {@code salon.status.changed} with the owner's contact resolved.
     *
     * <p>Best-effort on the LOOKUP, never on the publish: if bmp-user is unreachable we still
     * emit the event with null contact rather than dropping it, because the dispatcher logs an
     * undeliverable notification loudly and that is recoverable — whereas a status change that
     * emitted nothing is invisible forever.
     */
    private void publishStatusChange(Salon salon, String status, String note) {
        UUID ownerUserId = null;
        String ownerName = null;
        String email = null;
        String phone = null;

        try {
            ownerUserId = staff.findBySalonId(salon.getId()).stream()
                    .filter(s -> "OWNER".equalsIgnoreCase(s.getRole()))
                    .map(com.bmp.salon.entities.SalonStaff::getUserId)
                    .findFirst()
                    .orElse(null);

            if (ownerUserId != null) {
                var user = users.getUserById(ownerUserId).getBody();
                if (user != null) {
                    ownerName = user.name();
                    email = user.email();
                    phone = user.phone();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve the owner of salon {} for a status notification ({}). "
                    + "Publishing anyway with no contact — the dispatcher will say it couldn't "
                    + "deliver, which is recoverable; dropping the event would not be.",
                    salon.getId(), e.toString());
        }

        outbox.publish(new com.bmp.common.events.SalonStatusChanged(
                salon.getId(), salon.getReference(), salon.getName(), status, ownerUserId,
                ownerName, email, phone,
                note,
                // Only a rejection is actionable by the owner. Saying "resubmit" on a suspension
                // would point them at a button the API refuses.
                "rejected".equals(status)));
    }

    /**
     * Why isn't this salon getting bookings?
     *
     * <p>Nearly always one of three things, in this order of frequency:
     * <ol>
     *   <li>No stylist has any working hours set, so the availability algorithm has nothing to
     *       offer. This is by far the most common, and completely invisible from the salon's
     *       side — their profile looks fine.</li>
     *   <li>Everyone is switched off for today.</li>
     *   <li>The salon isn't approved.</li>
     * </ol>
     *
     * <p>Computing this here rather than making an agent click through three screens is the
     * difference between diagnosing it in ten seconds and escalating it to engineering.
     */
    @Operation(summary = "Diagnose a salon", description = "Returns the salon plus the most likely reason it isn't receiving bookings.")
    @GetMapping("/{salonId}/support-summary")
    public SalonSupportSummary supportSummary(@PathVariable UUID salonId) {
        Salon salon = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        List<StylistSalon> links = stylistSalons.findBySalonIdAndStatus(salonId, "active");
        int activeToday = (int) links.stream().filter(StylistSalon::isAvailableToday).count();

        // A stylist with no weekly_template rows produces no slots, whatever else is true.
        int withHours = (int) links.stream()
                .filter(l -> !availability
                        .findByStylistIdAndSalonIdAndRuleTypeOrderByDayOfWeekAscStartTimeAsc(
                                l.getStylistId(), salonId, "weekly_template")
                        .isEmpty())
                .count();

        String warning = null;
        if (!"approved".equalsIgnoreCase(salon.getStatus())) {
            warning = "This salon is '" + salon.getStatus() + "' — customers cannot see it at all until it is approved.";
        } else if (links.isEmpty()) {
            warning = "No stylists are linked to this salon, so there is nobody to book with.";
        } else if (withHours == 0) {
            warning = "No stylist has working hours set, so the salon offers no bookable slots. "
                    + "This is the usual cause and it isn't visible from the salon's own screens.";
        } else if (activeToday == 0) {
            warning = "Every stylist is marked unavailable today. Nothing can be booked for today, "
                    + "though future days are unaffected.";
        }

        return new SalonSupportSummary(
                salon.getId(), salon.getName(), salon.getLocation(), salon.getStatus(),
                links.size(), activeToday, withHours, warning);
    }

    // ── moderation ────────────────────────────────────────────────────────────────────────────

    /**
     * @param reason why it came down. Required — logged and audited, and the answer to the owner
     *               when they ask where their photo went.
     */
    public record RemovePhotoRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 500) String reason) {}

    public record PhotoRemoved(UUID photoId, UUID salonId) {}

    /**
     * Take a reported photo down. Session 60, closing the moderation TODO in bmp-admin.
     *
     * <h2>Delete, not hide — and only here</h2>
     * A review is hidden because {@code booking_id} is unique and the rating is history. A photo has
     * neither property: nothing references it, and V015's storage reconciliation already handles
     * the file. Keeping a hidden-photo row would add a column and a filter to every gallery read to
     * preserve something nobody will ever restore.
     *
     * <p>The asymmetry with reviews is deliberate and worth stating, because "we hide reviews but
     * delete photos" looks like an inconsistency until you know that one of them is referenced.
     *
     * <p>Idempotent by way of 404: a photo removed twice is a photo that is gone, and bmp-admin
     * treats NOT_FOUND on this call as success for exactly that reason.
     */
    @Operation(summary = "Remove a reported photo",
               description = "SERVICE only, called by bmp-admin when a content report is upheld. The stored file goes too.")
    @PostMapping("/photos/{photoId}/remove")
    public PhotoRemoved removePhoto(@PathVariable UUID photoId,
                                     @jakarta.validation.Valid @RequestBody RemovePhotoRequest req) {
        UUID salonId = salonService.removePhotoByModerator(photoId, req.reason());
        return new PhotoRemoved(photoId, salonId);
    }
}
