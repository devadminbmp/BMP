package com.bmp.salon.internal.controller;

import com.bmp.salon.internal.dto.SalonDtos.*;
import com.bmp.salon.internal.service.SalonService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-23: salon_schema.salon / salon_policy / salon_hours / salon_service CRUD. */
@RestController
public class SalonController {

    private final SalonService service;

    public SalonController(SalonService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/salons")
    public ResponseEntity<SalonResponse> create(@Valid @RequestBody CreateSalonRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/api/v1/salons/{salonId}")
    public SalonResponse getById(@PathVariable UUID salonId) {
        return service.getById(salonId);
    }

    @PutMapping("/api/v1/salons/{salonId}")
    public SalonResponse update(@PathVariable UUID salonId, @RequestBody UpdateSalonRequest req) {
        return service.update(salonId, req);
    }

    @GetMapping("/api/v1/salons")
    public List<NearbySalonResponse> near(@RequestParam String near,
                                           @RequestParam(defaultValue = "5") double radiusKm) {
        String[] parts = near.split(",");
        return service.near(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), radiusKm);
    }

    @PostMapping("/api/v1/salons/{salonId}/policy")
    public ResponseEntity<PolicyResponse> createPolicy(@PathVariable UUID salonId, @Valid @RequestBody PolicyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upsertPolicy(salonId, req));
    }

    @GetMapping("/api/v1/salons/{salonId}/policy")
    public PolicyResponse getPolicy(@PathVariable UUID salonId) {
        return service.getPolicy(salonId);
    }

    @PutMapping("/api/v1/salons/{salonId}/hours")
    public HoursResponse upsertHours(@PathVariable UUID salonId, @Valid @RequestBody HoursRequest req) {
        return service.upsertHours(salonId, req);
    }

    @PostMapping("/api/v1/salons/{salonId}/services")
    public ResponseEntity<ServiceResponse> addService(@PathVariable UUID salonId, @Valid @RequestBody ServiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addService(salonId, req));
    }

    @GetMapping("/api/v1/salons/{salonId}/services")
    public List<ServiceResponse> listServices(@PathVariable UUID salonId) {
        return service.listServices(salonId);
    }
}
