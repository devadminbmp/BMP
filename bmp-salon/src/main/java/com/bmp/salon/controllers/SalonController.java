package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.dto.SalonDtos.*;
import com.bmp.salon.services.SalonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    public SalonController(SalonService service) {
        this.service = service;
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
                                           @RequestParam(required = false) String category) {
        String[] parts = near.split(",");
        return service.near(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), radiusKm, category);
    }

    /**
     * OWNER only — not MANAGER.
     *
     * <p>The policy decides what a customer is charged when they cancel late. That is a
     * commercial decision the business owner makes, not a floor-management one, and it is
     * frozen onto every booking made afterwards. A manager changing it during a busy Saturday
     * would silently alter the terms of bookings taken from that moment on.
     */
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

    @Operation(summary = "List this salon's bookable services")
    @GetMapping("/api/v1/salons/{salonId}/services")
    public List<ServiceResponse> listServices(@PathVariable UUID salonId) {
        return service.listServices(salonId);
    }
}
