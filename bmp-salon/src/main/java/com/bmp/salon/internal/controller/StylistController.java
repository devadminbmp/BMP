package com.bmp.salon.internal.controller;

import com.bmp.salon.internal.dto.StylistDtos.*;
import com.bmp.salon.internal.service.StylistCrudService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-24: salon_schema.stylist / stylist_salon / stylist_service CRUD. NO DELETE endpoints — see markAlumni. */
@RestController
public class StylistController {

    private final StylistCrudService service;

    public StylistController(StylistCrudService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/stylists")
    public ResponseEntity<StylistResponse> create(@Valid @RequestBody CreateStylistRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/api/v1/stylists/{stylistId}")
    public StylistResponse getById(@PathVariable UUID stylistId) {
        return service.getById(stylistId);
    }

    @PostMapping("/api/v1/salons/{salonId}/stylists")
    public ResponseEntity<StylistSalonResponse> link(@PathVariable UUID salonId, @Valid @RequestBody LinkStylistRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.link(salonId, req));
    }

    @GetMapping("/api/v1/salons/{salonId}/stylists")
    public List<StylistSalonResponse> listForSalon(@PathVariable UUID salonId, @RequestParam(required = false) String status) {
        return service.listForSalon(salonId, status);
    }

    @PutMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/available-today")
    public AvailableTodayResponse setAvailableToday(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                                     @RequestBody AvailableTodayRequest req) {
        return service.setAvailableToday(salonId, stylistId, req);
    }

    @PostMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/alumni")
    public StylistSalonResponse markAlumni(@PathVariable UUID salonId, @PathVariable UUID stylistId) {
        return service.markAlumni(salonId, stylistId);
    }

    @PostMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/services")
    public ResponseEntity<StylistServiceResponse> addService(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                                              @Valid @RequestBody StylistServiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addService(salonId, stylistId, req));
    }

    @GetMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/services")
    public List<StylistServiceResponse> listServices(@PathVariable UUID salonId, @PathVariable UUID stylistId) {
        return service.listServices(salonId, stylistId);
    }
}
