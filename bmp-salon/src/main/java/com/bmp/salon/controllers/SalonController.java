package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.dto.SalonDtos.*;
import com.bmp.salon.services.SalonClosureService;
import com.bmp.salon.services.SalonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-23: salon_schema.salon / salon_policy / salon_hours / salon_service CRUD. */
@Tag(name = "Salons", description = "salon / salon_policy / salon_hours / salon_service CRUD. `location` is a plain \"lat,lng\" string — proximity search is in-memory Haversine, not real PostGIS.")
@RestController
public class SalonController {

    private final SalonService service;
    /** V019 (Session 48) — holidays and short closures. */
    private final SalonClosureService closures;

    public SalonController(SalonService service, SalonClosureService closures) {
        this.service = service;
        this.closures = closures;
    }

    /** Session 6: any authenticated SALON_OWNER can create a salon and becomes its OWNER
     * (see SalonService.create / StaffService.addOwner). Ownership of a specific EXISTING
     * salon (for policy/hours/services edits below) isn't enforced yet in this pass — those
     * endpoints stay open pending a follow-up authorization ticket; see CONTEXT.md. */
    @Operation(
        summary = "Create a salon — requires a SALON_OWNER token",
        description = "The calling user automatically becomes this salon's OWNER (a new salon_staff row is created for them). No invite needed for your own salon.")
    @PostMapping("/api/v1/salons")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ResponseEntity<SalonResponse> create(@Valid @RequestBody CreateSalonRequest req,
                                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, caller.userId()));
    }

    /**
     * The salon page a CUSTOMER sees, in one call. V011 (Session 40).
     *
     * <h2>Why this is a new endpoint rather than a change to {@code getById}</h2>
     * {@code getById} is the administrative view: it returns {@code status}, the assignment
     * strategy and timestamps, and it serves any salon regardless of whether it's approved —
     * bmp-booking and the console both rely on that. This one only ever serves publicly visible
     * salons and returns what a customer needs to choose. Two audiences, two shapes.
     *
     * <p>Public on purpose: browsing is the one thing that must work before signing in. The
     * product decision from Session 12 is browse-freely, gate at Book.
     */
    @Operation(
        summary = "Everything the salon page needs, in one call",
        description = "Services, stylists, categories, the cheapest price and a readable opening-"
            + "hours line. One round trip because this is the conversion funnel — three sequential "
            + "requests on a 4G connection is three spinners and two extra chances to fail. "
            + "404 for a salon that isn't publicly visible: an approved-looking page for an "
            + "unapproved salon is worse than no page.")
    @GetMapping("/api/v1/salons/{salonId}/detail")
    public SalonDetailResponse detail(@PathVariable UUID salonId) {
        return service.detail(salonId);
    }

    @Operation(summary = "Get a salon by id")
    @GetMapping("/api/v1/salons/{salonId}")
    public SalonResponse getById(@PathVariable UUID salonId) {
        return service.getById(salonId);
    }

    /**
     * OWNER or MANAGER **of this salon**.
     *
     * <p>The old javadoc said "not yet restricted… open pending a follow-up authorization
     * ticket". The follow-up never came, and until Session 29 <b>anyone at all could rename,
     * relocate or re-describe any salon on the platform without even being logged in</b>.
     *
     * <p>Note the second half of the expression. {@code hasAnyRole('SALON_OWNER','MANAGER')}
     * alone would let the owner of salon A edit salon B — every salon owner in Bengaluru is a
     * SALON_OWNER. The role says what KIND of thing you are; {@code principal.salonId()} says
     * WHICH one you belong to, and both are needed. This is the single most repeated mistake in
     * role-based systems and it is invisible in testing, because you usually test with one
     * tenant.
     */
    /**
     * Edit a salon's own profile.
     *
     * <h2>SESSION 40: THIS ENDPOINT HAD NO AUTHORIZATION AT ALL</h2>
     * No {@code @PreAuthorize}, and {@code UpdateSalonRequest} carries {@code status}. So <b>any
     * logged-in user could approve their own salon</b> — {@code PUT {"status":"approved"}} —
     * skipping moderation entirely and becoming publicly bookable. They could also rename or
     * relocate <b>any other salon on the platform</b> by changing the id in the URL.
     *
     * <p>Same class as the five holes found in Session 29, and missed by that sweep because the
     * sweep looked for endpoints with no credential — this one needs a login, just not the right
     * one. "Authenticated" is not "authorised", and a matcher that only asks the first question
     * will keep finding this endpoint acceptable.
     *
     * <h2>Two gates now</h2>
     * <ol>
     *   <li>{@code principal.salonId().equals(#salonId)} — the same expression every other
     *       salon-scoped endpoint here uses. The role says what KIND of person you are; the
     *       claim says WHICH salon, and only the second keeps an owner out of another shop.</li>
     *   <li>{@code status} is rejected outright — see {@code SalonService.update}. Moderation is
     *       bmp-admin's, through the internal endpoint. A salon approving itself is not an
     *       authorization bug that needs a narrower role; it is a field that must not be on this
     *       request at all.</li>
     * </ol>
     *
     * <p>OWNER only, not MANAGER: this is the business's identity — its name, address and public
     * description. A manager runs the desk.
     */
    @Operation(
        summary = "Edit the salon's profile",
        description = "Name, area, address, description, photo, categories and where booking "
            + "alerts go. Every field is null-means-unchanged, so a client that doesn't know about "
            + "a field can't blank it. `status` is REJECTED — approval is moderation, not "
            + "self-service.")
    @PreAuthorize("hasRole('SALON_OWNER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}")
    public SalonResponse update(@PathVariable UUID salonId, @RequestBody UpdateSalonRequest req) {
        return service.update(salonId, req);
    }

    @Operation(summary = "Find nearby salons", description = "`near` is \"lat,lng\". Distance is a plain-Haversine calculation over every salon in the DB, not a spatial index query — fine at current scale, won't be forever.")
    @GetMapping("/api/v1/salons")
    public List<NearbySalonResponse> near(@RequestParam String near,
                                           @RequestParam(defaultValue = "5") double radiusKm,
                                           // V011 (Session 40). "Salons that do hair colour" —
                                           // the query the category child table exists for.
                                           @RequestParam(required = false) String category,
                                           // Session 45. Free text over salon name, area, address
                                           // AND live service names. Service names are the point:
                                           // a customer knows they want "balayage" and has no idea
                                           // which salon does it — which is the whole question the
                                           // product exists to answer, and it matched nothing
                                           // until now. The app used to filter name/area itself,
                                           // over the list it had ALREADY fetched, so a salon
                                           // outside the radius was simply unfindable.
                                           @RequestParam(required = false) String q) {
        String[] parts = near.split(",");
        double lat, lng;
        try {
            lat = Double.parseDouble(parts[0].trim());
            lng = Double.parseDouble(parts[1].trim());
        } catch (RuntimeException e) {
            // Was an unguarded parseDouble: a malformed `near` produced a 500, which reads as
            // "BMP is broken" rather than "that request was wrong".
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BAD_LOCATION: `near` must be \"lat,lng\", e.g. 12.9716,77.5946");
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BAD_LOCATION: coordinates out of range.");
        }
        return service.near(lat, lng, radiusKm, category, q);
    }

    /**
     * OWNER only — not MANAGER.
     *
     * <p>The policy decides what a customer is charged when they cancel late. That is a
     * commercial decision the business owner makes, not a floor-management one, and it is
     * frozen onto every booking made afterwards. A manager changing it during a busy Saturday
     * would silently alter the terms of bookings taken from that moment on.
     */
    /**
     * Set the salon's map position. OWNER <b>or</b> MANAGER. Session 45.
     *
     * <h2>Why this isn't just a field on PUT /salons/{salonId}</h2>
     * That endpoint accepts {@code location} too, and it is {@code SALON_OWNER}-only — correctly,
     * because it can also rename the business and change where booking alerts go. Widening it so a
     * manager could fix the map pin would hand them the rest of that surface as well.
     *
     * <p>So the pin gets its own endpoint, on the same principle as the photo endpoints: the
     * decision about WHAT THE BUSINESS IS stays with the owner, while the day-to-day work of
     * describing the shop is open to whoever is running it. A manager standing in the salon with
     * their phone is the person best placed to set this, and making them ask the owner is how it
     * stays wrong.
     *
     * <h2>This is the highest-consequence field on the salon</h2>
     * Discovery sorts every customer's list by distance from them, so a wrong pin doesn't rank the
     * salon lower — it removes it from the results of everyone genuinely nearby, and puts it in
     * front of people who will never travel there. Nothing in the product would ever show the
     * owner why the bookings stopped. Hence the range check below: a swapped lat/lng, or a pasted
     * value with a stray digit, is silently catastrophic and cheap to reject.
     */
    @Operation(summary = "Set the salon's map position",
            description = "Owner or manager. This decides which customers find the salon — "
                    + "discovery is sorted by distance from the customer, so a wrong pin makes the "
                    + "salon invisible to everyone nearby.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}/location")
    public SalonResponse updateLocation(@PathVariable UUID salonId,
                                         @Valid @RequestBody LatLng location) {
        if (location.lat() < -90 || location.lat() > 90
                || location.lng() < -180 || location.lng() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BAD_LOCATION: coordinates out of range.");
        }
        // 0,0 is Null Island in the Gulf of Guinea — never a salon, and the classic signature of
        // an uninitialised value that got saved. Rejecting it costs nothing and catches a bug
        // whose only other symptom is silence.
        if (location.lat() == 0 && location.lng() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BAD_LOCATION: 0,0 is in the Atlantic Ocean. Please set a real position.");
        }
        return service.updateLocation(salonId, location);
    }

    /**
     * "Where is my application?" — the owner's own approval status. Session 46.
     *
     * <p>OWNER or MANAGER of this salon. A manager arriving at a desk that does nothing needs the
     * same explanation the owner does, and withholding it would just produce a phone call.
     *
     * <p>Not public: an arbitrary user must not be able to enumerate which salons were rejected
     * and read a moderator's private note about them.
     */
    @Operation(summary = "This salon's approval status",
            description = "Pending, approved, rejected or suspended — and on rejection the "
                    + "moderator's reason, which is what tells the owner what to fix.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @GetMapping("/api/v1/salons/{salonId}/approval")
    public SalonApprovalResponse approval(@PathVariable UUID salonId) {
        return service.approvalStatus(salonId);
    }

    /**
     * Resubmit after a rejection. OWNER only.
     *
     * <p>Manager is excluded here and included on the read above, deliberately. Reading the status
     * is information a manager needs to do their job; putting the business back in front of
     * moderation is a decision about the business itself, which is the same line every other
     * owner-only endpoint draws.
     */
    @Operation(summary = "Resubmit this salon for review after a rejection",
            description = "Owner only. 409 unless the salon is actually rejected. Creates a new "
                    + "review rather than reopening the old one, so the rejection and its reason "
                    + "survive.")
    @PreAuthorize("hasRole('SALON_OWNER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/resubmit")
    public SalonApprovalResponse resubmit(@PathVariable UUID salonId,
                                           @RequestBody(required = false) ResubmitRequest req) {
        return service.resubmitForReview(salonId, req == null ? null : req.note());
    }

    // ══ V019 (Session 48): closures — holidays, half-days, "shut for two hours" ══════════════

    @Operation(
        summary = "This salon's closures",
        description = "Owner or manager. Newest window first. Includes cancelled ones — a closure customers were already told about is still part of the record.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @GetMapping("/api/v1/salons/{salonId}/closures")
    public java.util.List<ClosureResponse> listClosures(@PathVariable UUID salonId) {
        return closures.list(salonId).stream().map(this::toClosureResponse).toList();
    }

    @Operation(
        summary = "Close the salon for a window",
        description = "Blocks NEW bookings inside the window immediately. Does NOT cancel bookings that already exist — those are returned as a count so the owner can reschedule or refund each one. Recording a closure and destroying somebody's appointment are different acts.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/closures")
    @ResponseStatus(HttpStatus.CREATED)
    public ClosureResponse createClosure(@PathVariable UUID salonId,
                                          @Valid @RequestBody CreateClosureRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser caller) {
        return toClosureResponse(closures.create(
                salonId, req.startsAt(), req.endsAt(), req.reason(),
                caller == null ? null : caller.userId()));
    }

    @Operation(
        summary = "Reopen — cancel a closure",
        description = "Soft-cancels. The row stays so we can tell affected customers it's back on, and so the reason a booking was moved survives.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @DeleteMapping("/api/v1/salons/{salonId}/closures/{closureId}")
    public ClosureResponse cancelClosure(@PathVariable UUID salonId, @PathVariable UUID closureId) {
        return toClosureResponse(closures.cancel(salonId, closureId));
    }

    @Operation(
        summary = "Who is affected by this closure window",
        description = "Live bookings inside the window, with the customer's name and email so the owner can act. Excludes cancelled, completed and no-show. Ask BEFORE creating the closure to preview the damage, or after, to work through the list.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @GetMapping("/api/v1/salons/{salonId}/closures/affected")
    public java.util.List<com.bmp.salon.client.BookingServiceClient.AffectedBooking> affectedByClosure(
            @PathVariable UUID salonId,
            @RequestParam java.time.Instant from,
            @RequestParam java.time.Instant to) {
        // Deliberately NOT caught: if bmp-booking is down the owner must see an error, not an
        // empty list that reads as "nobody is affected". See SalonClosureService.affectedBookings.
        return closures.affectedBookings(salonId, from, to);
    }

    /**
     * @implNote {@code affectedBookings} is -1 when bmp-booking could not be reached — never 0.
     * Zero is a claim that nobody is stranded, and being confidently wrong about that is what puts
     * a customer in front of a locked shutter. The UI renders -1 as "couldn't check".
     */
    private ClosureResponse toClosureResponse(com.bmp.salon.entities.SalonClosure c) {
        return new ClosureResponse(c.getId(), c.getStartsAt(), c.getEndsAt(), c.getReason(),
                c.isActive(), c.getCreatedAt(),
                // Only meaningful for a closure that is still in force; a cancelled one strands
                // nobody, and asking bmp-booking about it is a call with no consumer.
                c.isActive()
                        ? closures.affectedCountOrUnknown(c.getSalonId(), c.getStartsAt(), c.getEndsAt())
                        : 0);
    }

    @Operation(
        summary = "Go live — publish this salon to customers",
        description = "OWNER only. Approval means you passed review; this is what actually puts you in front of customers. Refused with a reason if the salon is still pending, rejected or suspended, or if it has no live service — a salon customers cannot book is worse than one they cannot find.")
    @PreAuthorize("hasRole('SALON_OWNER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/go-live")
    public SalonApprovalResponse goLive(@PathVariable UUID salonId) {
        return service.goLive(salonId);
    }

    @Operation(summary = "Create or replace this salon's cancellation/prepayment policy", description = "OWNER of this salon only — it sets what customers are charged on late cancellation.")
    @PreAuthorize("hasRole('SALON_OWNER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/policy")
    public ResponseEntity<PolicyResponse> createPolicy(@PathVariable UUID salonId, @Valid @RequestBody PolicyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upsertPolicy(salonId, req));
    }

    @Operation(summary = "Get this salon's policy")
    @GetMapping("/api/v1/salons/{salonId}/policy")
    public PolicyResponse getPolicy(@PathVariable UUID salonId) {
        return service.getPolicy(salonId);
    }

    /**
     * Read the salon's opening hours. Session 51.
     *
     * <h2>Why this was missing, and why that mattered</h2>
     * Only the PUT existed. An owner could overwrite all seven days and had no way to load what
     * was already set — so any editor built on it would open blank, and saving would silently
     * replace real hours with whatever the form happened to default to.
     *
     * <p>PUBLIC, and intentionally so: a customer browsing a salon is already shown an
     * {@code openHours} display line composed from these rows. This exposes the same facts in a
     * structured form and reveals nothing new.
     */
    @Operation(summary = "The salon's opening hours, one entry per configured day",
               description = "Public. Days with no entry are closed. Sunday = 0.")
    @GetMapping("/api/v1/salons/{salonId}/hours")
    public HoursResponse getHours(@PathVariable UUID salonId) {
        return service.getHours(salonId);
    }

    /**
     * OWNER or MANAGER of this salon. Opening hours are day-to-day floor management — a manager
     * closing early for a power cut is exactly the case this exists for.
     */
    @Operation(summary = "Set opening hours for all 7 days at once", description = "Owner or manager OF THIS SALON. Idempotent upsert — re-sending the same body doesn't duplicate rows.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}/hours")
    public HoursResponse upsertHours(@PathVariable UUID salonId, @Valid @RequestBody HoursRequest req) {
        return service.upsertHours(salonId, req);
    }

    /**
     * OWNER or MANAGER of this salon.
     *
     * <p>This sets a PRICE, and until Session 29 it needed no credential — anyone could have
     * added a ₹1 service to any salon's menu and booked it. Prices are snapshotted onto
     * bookings at creation, so a bad row here becomes a commitment the salon has to honour.
     */
    @Operation(summary = "Add a bookable service to this salon", description = "Owner or manager OF THIS SALON only — this sets a price customers can book at.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/services")
    public ResponseEntity<ServiceResponse> addService(@PathVariable UUID salonId, @Valid @RequestBody ServiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addService(salonId, req));
    }

    @Operation(
        summary = "List this salon's services",
        description = "Live services only by default. `includeArchived=true` also returns retired "
            + "ones (greyed in the owner's editor so they can be brought back) — never pass it "
            + "from a customer-facing screen.")
    @GetMapping("/api/v1/salons/{salonId}/services")
    public List<ServiceResponse> listServices(
            @PathVariable UUID salonId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.listServices(salonId, includeArchived);
    }

    /**
     * Edit a service. V012 (Session 44).
     *
     * <h2>Why editing is safe, and why that isn't obvious</h2>
     * Changing a price looks like it should be dangerous — bookings reference services. It isn't,
     * because {@code booking_service_item} freezes {@code name_snapshot},
     * {@code price_paise_snapshot} and {@code duration_shown_minutes} at creation. What the salon
     * charges tomorrow and what it charged last Tuesday are separate facts, and the schema has
     * kept them separate since V002.
     *
     * <p>OWNER or MANAGER: adjusting a price or a duration is day-to-day trade, and a manager who
     * can take the booking can reasonably fix "45 min" that should have said "60".
     */
    @Operation(
        summary = "Edit a service",
        description = "Every field is null-means-unchanged, so a client that doesn't know about a "
            + "field can't blank it. Existing bookings are untouched — they froze name, price and "
            + "duration when they were made.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}/services/{serviceId}")
    public ServiceResponse updateService(@PathVariable UUID salonId, @PathVariable UUID serviceId,
                                          @Valid @RequestBody UpdateServiceRequest req) {
        return service.updateService(salonId, serviceId, req);
    }

    /**
     * Retire a service — <b>archive, not delete</b>.
     *
     * <p>DELETE is the verb because that's what the client means and what REST expects; what
     * happens underneath is a soft archive, because a hard delete breaks three things at once:
     * {@code stylist_service} and {@code salon_combo_item} hold real FKs, and
     * {@code booking_service_item.service_id} is a cross-service logical ref with no FK to stop
     * it. See V012's header. The response body returns the archived row so the client can show
     * it greyed rather than guessing.
     *
     * <p>OWNER only. Adding a service is operational; removing one from the menu is a commercial
     * decision about what the business sells.
     */
    @Operation(
        summary = "Retire a service (archive, not delete)",
        description = "Removes it from the customer menu and blocks new bookings. Past bookings, "
            + "combos and stylist skill lists that reference it keep working — nothing is deleted. "
            + "Reversible via the restore endpoint.")
    @PreAuthorize("hasRole('SALON_OWNER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @DeleteMapping("/api/v1/salons/{salonId}/services/{serviceId}")
    public ServiceResponse archiveService(@PathVariable UUID salonId, @PathVariable UUID serviceId) {
        return service.archiveService(salonId, serviceId);
    }

    @Operation(summary = "Put a retired service back on the menu")
    @PreAuthorize("hasRole('SALON_OWNER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/services/{serviceId}/restore")
    public ServiceResponse restoreService(@PathVariable UUID salonId, @PathVariable UUID serviceId) {
        return service.restoreService(salonId, serviceId);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // V014 (Session 44) — the salon gallery
    // ═════════════════════════════════════════════════════════════════════════════════════════
    //
    // The salon had ONE image_url: enough to identify it in a list, nowhere near enough to choose
    // it. Customers pick a salon by looking at it.
    //
    // THE READ IS PUBLIC, and that is a deliberate contrast with SalonComboController, whose
    // reads are restricted. The rule both follow is the same: open exactly as much as the product
    // needs. Photos exist ONLY to be seen by a customer deciding whether to book, so gating them
    // would defeat the feature; combos have no customer screen yet, so opening their reads would
    // be widening access for a hypothetical.

    @Operation(
        summary = "The salon's photo gallery",
        description = "PUBLIC — this is what a customer browses before booking. Ordered by the "
            + "owner's choice. Empty is normal; the UI falls back to the single card image.")
    @GetMapping("/api/v1/salons/{salonId}/photos")
    public List<SalonPhotoResponse> listPhotos(@PathVariable UUID salonId) {
        return service.listPhotos(salonId);
    }

    /**
     * OWNER or MANAGER of this salon. A manager arranging the shop's photos is ordinary
     * day-to-day work — this isn't the business's legal identity, it's the shop window.
     */
    @Operation(
        summary = "Add a photo to the gallery",
        description = "`url` must be http/https — on the web build these land in an image source, "
            + "so a permissive field would be stored XSS aimed at customers. **There is no upload "
            + "endpoint**: this is a link to an image hosted elsewhere. Capped at 12 photos; the "
            + "13th is a 409 that says so.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/photos")
    public ResponseEntity<SalonPhotoResponse> addPhoto(@PathVariable UUID salonId,
                                                        @Valid @RequestBody SalonPhotoRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addPhoto(salonId, req));
    }

    @Operation(summary = "Edit a photo's caption or position", description = "Null fields unchanged.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}/photos/{photoId}")
    public SalonPhotoResponse updatePhoto(@PathVariable UUID salonId, @PathVariable UUID photoId,
                                           @RequestBody SalonPhotoRequest req) {
        return service.updatePhoto(salonId, photoId, req);
    }

    /**
     * A genuine DELETE, unlike a service — and the contrast is the point.
     *
     * <p>Nothing references a photo: no booking snapshots it, no other table points at it. There
     * is no history to orphan, so there is nothing to archive. {@code salon_service} archives
     * because it IS referenced, not out of a general reluctance to delete.
     */
    @Operation(summary = "Remove a photo", description = "A real delete — nothing references a photo.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @DeleteMapping("/api/v1/salons/{salonId}/photos/{photoId}")
    public ResponseEntity<Void> deletePhoto(@PathVariable UUID salonId, @PathVariable UUID photoId) {
        service.deletePhoto(salonId, photoId);
        return ResponseEntity.noContent().build();
    }
}
