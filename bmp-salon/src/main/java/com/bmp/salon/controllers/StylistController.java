package com.bmp.salon.controllers;

import com.bmp.salon.dto.StylistDtos.*;
import com.bmp.salon.services.StylistCrudService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @Operation(summary = "Create a stylist profile", description = "Normally called by bmp-auth during STYLIST signup, not directly.")
    @PostMapping("/api/v1/stylists")
    public ResponseEntity<StylistResponse> create(@Valid @RequestBody CreateStylistRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a stylist profile by id")
    @GetMapping("/api/v1/stylists/{stylistId}")
    public StylistResponse getById(@PathVariable UUID stylistId) {
        return service.getById(stylistId);
    }

    @Operation(summary = "Link a stylist to a salon", description = "Creates an active stylist_salon row. This is how a stylist joins a specific salon after signing up — no invite/approval step in this pass.")
    @PostMapping("/api/v1/salons/{salonId}/stylists")
    public ResponseEntity<StylistSalonResponse> link(@PathVariable UUID salonId, @Valid @RequestBody LinkStylistRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.link(salonId, req));
    }

    @Operation(summary = "List a salon's stylists", description = "Optionally filter by stylist_salon status (active/alumni/...).")
    @GetMapping("/api/v1/salons/{salonId}/stylists")
    public List<StylistSalonResponse> listForSalon(@PathVariable UUID salonId, @RequestParam(required = false) String status) {
        return service.listForSalon(salonId, status);
    }

    @Operation(summary = "Set whether a stylist is available today", description = "The cheapest, fastest check in the availability algorithm — kept as its own tiny endpoint on purpose.")
    @PutMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/available-today")
    public AvailableTodayResponse setAvailableToday(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                                     @RequestBody AvailableTodayRequest req) {
        return service.setAvailableToday(salonId, stylistId, req);
    }

    @Operation(
        summary = "Mark a stylist as alumni of this salon (NOT a delete)",
        description = "Freezes salon_rating/salon_review_count at their current value permanently and sets left_at. This is how a stylist \"leaves\" a salon in this system — their history is never deleted.")
    @PostMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/alumni")
    public StylistSalonResponse markAlumni(@PathVariable UUID salonId, @PathVariable UUID stylistId) {
        return service.markAlumni(salonId, stylistId);
    }

    @Operation(summary = "Add a service this stylist performs at this salon", description = "Optional per-stylist duration/price override on top of the salon's base service.")
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
