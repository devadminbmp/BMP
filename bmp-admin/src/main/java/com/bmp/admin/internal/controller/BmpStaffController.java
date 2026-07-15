package com.bmp.admin.internal.controller;

import com.bmp.admin.internal.dto.AdminDtos.*;
import com.bmp.admin.internal.service.BmpStaffService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-29: admin_schema.bmp_staff CRUD. password_hash never appears in any response. */
@RestController
@RequestMapping("/api/v1/staff")
public class BmpStaffController {

    private final BmpStaffService service;

    public BmpStaffController(BmpStaffService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StaffResponse> create(@Valid @RequestBody CreateStaffRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{staffId}")
    public StaffResponse getById(@PathVariable UUID staffId) {
        return service.getById(staffId);
    }

    @GetMapping
    public List<StaffResponse> list(@RequestParam(required = false) String role) {
        return service.list(role);
    }

    @PutMapping("/{staffId}/status")
    public StaffResponse updateStatus(@PathVariable UUID staffId, @Valid @RequestBody StaffStatusRequest req) {
        return service.updateStatus(staffId, req);
    }
}
