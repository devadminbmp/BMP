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

    @Operation(summary = "Get a salon by id")
    @GetMapping("/api/v1/salons/{salonId}")
    public SalonResponse getById(@PathVariable UUID salonId) {
        return service.getById(salonId);
    }

    @Operation(summary = "Update salon fields", description = "Not yet restricted to the salon's own OWNER/MANAGER — open pending a follow-up authorization ticket.")
    @PutMapping("/api/v1/salons/{salonId}")
    public SalonResponse update(@PathVariable UUID salonId, @RequestBody UpdateSalonRequest req) {
        return service.update(salonId, req);
    }

    @Operation(summary = "Find nearby salons", description = "`near` is \"lat,lng\". Distance is a plain-Haversine calculation over every salon in the DB, not a spatial index query — fine at current scale, won't be forever.")
    @GetMapping("/api/v1/salons")
    public List<NearbySalonResponse> near(@RequestParam String near,
                                           @RequestParam(defaultValue = "5") double radiusKm) {
        String[] parts = near.split(",");
        return service.near(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), radiusKm);
    }

    @Operation(summary = "Create or replace this salon's cancellation/prepayment policy")
    @PostMapping("/api/v1/salons/{salonId}/policy")
    public ResponseEntity<PolicyResponse> createPolicy(@PathVariable UUID salonId, @Valid @RequestBody PolicyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upsertPolicy(salonId, req));
    }

    @Operation(summary = "Get this salon's policy")
    @GetMapping("/api/v1/salons/{salonId}/policy")
    public PolicyResponse getPolicy(@PathVariable UUID salonId) {
        return service.getPolicy(salonId);
    }

    @Operation(summary = "Set opening hours for all 7 days at once", description = "Idempotent upsert — re-sending the same body doesn't duplicate rows.")
    @PutMapping("/api/v1/salons/{salonId}/hours")
    public HoursResponse upsertHours(@PathVariable UUID salonId, @Valid @RequestBody HoursRequest req) {
        return service.upsertHours(salonId, req);
    }

    @Operation(summary = "Add a bookable service to this salon")
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
