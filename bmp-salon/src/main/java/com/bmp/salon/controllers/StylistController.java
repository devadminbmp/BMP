package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.dto.StylistDtos.*;
import com.bmp.salon.services.StylistCrudService;
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

/** BMP-24: salon_schema.stylist / stylist_salon / stylist_service CRUD. NO DELETE endpoints — see markAlumni. */
@Tag(name = "Stylists", description = "stylist / stylist_salon / stylist_service — the portable-identity tables. A stylist profile is created once (STYLIST signup, bmp-auth) and can link to multiple salons over time via stylist_salon.")
@RestController
public class StylistController {

    private final StylistCrudService service;

    public StylistController(StylistCrudService service) {
        this.service = service;
    }

    /**
     * SERVICE, or a salon owner/manager adding a chair.
     *
     * <p>The usual caller is bmp-auth during stylist signup, which presents the internal key.
     * Salon staff also need it when listing a stylist who has no BMP account — most stylists
     * don't, which is why {@code stylist.user_id} is nullable.
     *
     * <p>No salon scope check is possible here: the request creates a stylist that isn't linked
     * to any salon yet. The scope is enforced one step later, on {@code link}.
     */
    @Operation(summary = "Create a stylist profile", description = "SERVICE (bmp-auth during signup) or any salon owner/manager adding a chair. Linking to a salon is a separate, salon-scoped call.")
    @PreAuthorize("hasAnyRole('SERVICE','SALON_OWNER','MANAGER')")
    @PostMapping("/api/v1/stylists")
    public ResponseEntity<StylistResponse> create(@Valid @RequestBody CreateStylistRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a stylist profile by id")
    @GetMapping("/api/v1/stylists/{stylistId}")
    public StylistResponse getById(@PathVariable UUID stylistId) {
        return service.getById(stylistId);
    }

    /**
     * OWNER or MANAGER of this salon, or SERVICE (invite consumption during stylist signup).
     *
     * <p>This is the endpoint that decides who appears on a salon's roster and is therefore
     * bookable there. Unprotected — as it was until Session 29 — anyone could have attached any
     * stylist to any salon, or attached a stylist to a competitor.
     */
    @Operation(summary = "Link a stylist to a salon", description = "Owner or manager OF THIS SALON, or an internal service. Creates an active stylist_salon row.")
    @PreAuthorize("hasRole('SERVICE') or (hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId))")
    @PostMapping("/api/v1/salons/{salonId}/stylists")
    public ResponseEntity<StylistSalonResponse> link(@PathVariable UUID salonId, @Valid @RequestBody LinkStylistRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.link(salonId, req));
    }

    @Operation(summary = "List a salon's stylists", description = "Optionally filter by stylist_salon status (active/alumni/...).")
    @GetMapping("/api/v1/salons/{salonId}/stylists")
    public List<StylistSalonResponse> listForSalon(@PathVariable UUID salonId, @RequestParam(required = false) String status) {
        return service.listForSalon(salonId, status);
    }

    /**
     * Correct a stylist's name or speciality. OWNER or MANAGER of this salon. Session 44.
     *
     * <h2>Why this didn't exist, and why that was a problem</h2>
     * This controller had exactly one {@code PUT} — {@code available-today} — so <b>a stylist's
     * name was write-once</b>. Salons quick-add staff mid-shift, often mishearing a name across a
     * busy floor, and that typo then sits on the public salon page and every desk row forever.
     *
     * <h2>The scope check lives in the service, and has to</h2>
     * A {@code Stylist} is a PORTABLE record — deliberately not owned by a salon, so a stylist who
     * changes shops keeps their identity and ratings. So this annotation proving the caller owns
     * {@code salonId} says nothing about {@code stylistId}; {@code StylistCrudService.update}
     * checks {@code stylist_salon} before touching anything. <b>Authorise the path, then trust the
     * body</b> is the exact shape of the Session 40 hole, and portable entities make it easy to
     * write again.
     */
    @Operation(
        summary = "Correct a stylist's name or speciality",
        description = "Owner or manager OF THIS SALON. Null fields are left unchanged; an empty "
            + "speciality clears it. Past bookings are unaffected — they froze the stylist's name "
            + "when they were made.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}/stylists/{stylistId}")
    public StylistResponse updateStylist(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                          @Valid @RequestBody UpdateStylistRequest req) {
        return service.update(salonId, stylistId, req);
    }

    /**
     * OWNER, MANAGER **or the STYLIST themselves** — the one endpoint here where a stylist is a
     * legitimate writer.
     *
     * <p>"I'm not in today" is the stylist's own call as much as the desk's, and the stylist
     * dashboard's availability toggle posts here. The scope check is still by salon: a stylist's
     * token carries the salon they work at, so they can only flip availability within it.
     *
     * <p>This is deliberately NOT narrowed to "only your own stylist row". A stylist's token
     * carries their USER id, and {@code stylistId} here is the salon-side stylist id — two
     * different identifiers, with no way to compare them in SpEL. Narrowing it properly needs
     * the check inside the service, where the mapping can be loaded. Tracked as a refinement:
     * today a stylist could flip a colleague's availability at the same salon, which is a
     * nuisance rather than a breach, and the alternative was leaving it open to everyone.
     */
    @Operation(summary = "Set whether a stylist is available today", description = "Owner, manager, or a stylist at this salon. The cheapest, fastest check in the availability algorithm.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER','STYLIST') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/available-today")
    public AvailableTodayResponse setAvailableToday(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                                     @RequestBody AvailableTodayRequest req) {
        return service.setAvailableToday(salonId, stylistId, req);
    }

    /**
     * Ends someone's employment relationship with this salon. Owner OR manager.
     *
     * <h2>Session 51 — managers were excluded, and the reason has expired</h2>
     * This used to be owner-only, and the javadoc said why:
     *
     * <blockquote>"Managers are excluded, and irreversibility is why: it freezes the stylist's
     * per-salon rating and review count permanently and sets left_at. <b>There is no un-alumni
     * endpoint.</b> A manager removing a colleague in a bad moment is not something you can
     * undo."</blockquote>
     *
     * <p>That was correct when it was written and is no longer true. Session 48 changed
     * {@code StylistCrudService.link} to REUSE an existing alumni row when a stylist is re-added:
     * status flips back to active, {@code left_at} clears, and the salon rating and review count
     * survive. Re-adding somebody is now one tap and costs them nothing.
     *
     * <p>So the argument for the restriction has gone, and what remains is a manager who runs the
     * floor every day being unable to do an ordinary piece of floor management — which mostly
     * means it gets done by sharing the owner's login, and that is worse for everyone.
     *
     * <p><b>The lesson worth keeping:</b> a guard justified by a limitation has to be revisited
     * when the limitation goes. This one outlived its reason by three sessions because the reason
     * lived in a comment nobody re-read while changing the thing it described.
     *
     * <h2>Still not a delete</h2>
     * The row is kept as {@code alumni} with a {@code left_at}. Past bookings point at somebody
     * who really did work here, and that has to stay true.
     */
    @Operation(
        summary = "Remove a stylist from this salon's team (NOT a delete)",
        description = "Owner or manager OF THIS SALON. Sets the link to alumni and records "
            + "left_at; their history is never deleted. Reversible — re-adding them restores the "
            + "same row, so their rating and review count at this salon survive.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/alumni")
    public StylistSalonResponse markAlumni(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                            @org.springframework.security.core.annotation.AuthenticationPrincipal
                                            com.bmp.common.security.AuthenticatedUser caller) {
        return service.markAlumni(salonId, stylistId,
                caller == null ? null : caller.userId(), "salon");
    }

    /**
     * Set which of the salon's services this stylist performs. Session 67.
     *
     * <h2>One endpoint where Session 66 had three</h2>
     * There used to be add-one, edit-its-numbers, and remove-one, because the model carried a
     * per-stylist duration and price. Darshan removed that concept — "timing is standard for
     * service and applicable all stylish" — leaving nothing per-row to edit.
     *
     * <p>What remains is a checkbox list against the salon's menu, so the endpoint takes the
     * resulting SET. That is not just tidier: a set has no partial-failure state, no ordering
     * question and no duplicate case, so three endpoints' worth of edge conditions stop existing
     * rather than getting handled.
     *
     * <p>PUT rather than POST because it is idempotent — sending the same set twice leaves the
     * same rows, which is what a save button should do when somebody taps it twice.
     */
    @Operation(
        summary = "Set which services this stylist performs here",
        description = "Owner or manager OF THIS SALON. Send the full set — anything omitted is removed. Duration and price come from the service itself and are the same for every stylist.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PutMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/services")
    public List<StylistServiceResponse> replaceServices(@PathVariable UUID salonId,
                                                         @PathVariable UUID stylistId,
                                                         @Valid @RequestBody StylistServiceRequest req) {
        return service.replaceServices(salonId, stylistId, req);
    }

    /**
     * Session 66 — THIS ENDPOINT HAD NO {@code @PreAuthorize} AT ALL.
     *
     * <p>It fell through to whatever the filter chain's default was, which is precisely the state
     * Session 84's audit set out to eliminate ("every endpoint states who may call it"). It is not
     * a disclosure emergency — a salon's service menu and its stylist list are both already public
     * — but an endpoint whose access rules are implicit is one nobody can review, and this one
     * gained write siblings today.
     *
     * <p>Scoped to the salon's own staff for now rather than made public. The customer-facing use
     * ("show me who does balayage") is deliberately NOT enabled here, because the availability
     * algorithm does not yet consult stylist_service — publishing the data before booking honours
     * it would let a customer pick a stylist for a service the system will happily let anyone take.
     */
    @Operation(
        summary = "List the services this stylist performs at this salon",
        description = "Owner or manager of this salon. Not public: the booking algorithm does not filter by this yet, so exposing it to customers would promise a match the booking flow does not enforce.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @GetMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/services")
    public List<StylistServiceResponse> listServices(@PathVariable UUID salonId, @PathVariable UUID stylistId) {
        return service.listServices(salonId, stylistId);
    }

    /**
     * A stylist's OWN specialisations. Session 66.
     *
     * <h2>Why the path says stylist-profile but the code lives here</h2>
     * The mapping this needs (join each row to the salon's service for its name and base price)
     * lives in StylistCrudService, and StylistCrudService already injects StylistSelfService.
     * Putting this on StylistSelfController would inject StylistCrudService back the other way —
     * a circular bean dependency Spring refuses at startup. Duplicating the mapper instead would
     * be worse: two copies of one join, which is the drift pattern this codebase keeps finding.
     * Spring does not care which class serves a path, and the path is what the client sees.
     *
     * <h2>Why it can't reuse the salon-scoped list above</h2>
     * A stylist's JWT never carries a salonId — bmp-auth's resolveSalonScope excludes stylists on
     * purpose, because a stylist is a portable profile and not a salon seat. Every guard on the
     * salon-scoped endpoints requires {@code principal.salonId()}, so a stylist calling one gets a
     * 403 regardless of entitlement. The salon is derived from their own active link instead.
     *
     * <h2>READ ONLY, and that is a decision rather than an omission</h2>
     * What a stylist charges, and how long the salon books them out for, is the salon's call. An
     * endpoint letting a stylist set their own price would be a self-granted authority of exactly
     * the kind StylistSelfService exists to prevent.
     */
    @Operation(
        summary = "What I'm set up to do at my salon",
        description = "The stylist's own view. Read only — pricing and duration are the salon's decision. Empty list when they're not currently on a team.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/api/v1/stylist-profile/services")
    public List<StylistServiceResponse> myServices(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.myServices(caller.userId());
    }

    /*
     * Session 67 — the per-row PUT and DELETE that lived here are gone.
     *
     * They existed to edit a per-stylist duration and price, and to undo a mistaken add. With
     * membership as the only fact left, the replace-set PUT above covers both: unticking a box IS
     * the removal, and there is nothing else on the row to change. Two endpoints removed rather
     * than kept "just in case" — an unused write endpoint is attack surface with no user.
     */
}
