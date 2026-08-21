package com.bmp.salon.controllers;

import com.bmp.salon.dto.StylistDtos.*;
import com.bmp.salon.services.StylistCrudService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
     * OWNER only. This ends someone's employment relationship with the salon.
     *
     * <p>Managers are excluded, and irreversibility is why: it freezes the stylist's per-salon
     * rating and review count permanently and sets {@code left_at}. There is no un-alumni
     * endpoint. A manager removing a colleague in a bad moment is not something you can undo.
     */
    @Operation(
        summary = "Mark a stylist as alumni of this salon (NOT a delete)",
        description = "OWNER of this salon only — irreversible. Freezes salon_rating/salon_review_count permanently and sets left_at. Their history is never deleted.")
    @PreAuthorize("hasRole('SALON_OWNER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/alumni")
    public StylistSalonResponse markAlumni(@PathVariable UUID salonId, @PathVariable UUID stylistId) {
        return service.markAlumni(salonId, stylistId);
    }

    /** OWNER or MANAGER of this salon — it can carry a per-stylist PRICE override. */
    @Operation(summary = "Add a service this stylist performs at this salon", description = "Owner or manager OF THIS SALON. Optional per-stylist duration/price override on top of the salon's base service.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/services")
    public ResponseEntity<StylistServiceResponse> addService(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                                              @Valid @RequestBody StylistServiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addService(salonId, stylistId, req));
    }

    @Operation(summary = "List the services this stylist performs at this salon")
    @GetMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/services")
    public List<StylistServiceResponse> listServices(@PathVariable UUID salonId, @PathVariable UUID stylistId) {
        return service.listServices(salonId, stylistId);
    }
}
