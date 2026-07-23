package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminDtos.*;
import com.bmp.admin.services.BmpStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-29: admin_schema.bmp_staff CRUD. password_hash never appears in any response. */
@Tag(name = "BMP Staff", description = "Internal staff accounts (bcrypt passwords). Separate identity space from user_schema.users — see this service's OpenApiConfig for the auth-gap caveat.")
@RestController
@RequestMapping("/api/v1/staff")
public class BmpStaffController {

    private final BmpStaffService service;

    public BmpStaffController(BmpStaffService service) {
        this.service = service;
    }

    @Operation(summary = "Create a staff account", description = "Password is bcrypt-hashed before storage; password_hash is never returned in any response.")
    @PostMapping
    public ResponseEntity<StaffResponse> create(@Valid @RequestBody CreateStaffRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a staff account by id")
    @GetMapping("/{staffId}")
    public StaffResponse getById(@PathVariable UUID staffId) {
        return service.getById(staffId);
    }

    @Operation(summary = "List staff accounts", description = "Optionally filtered by role.")
    @GetMapping
    public List<StaffResponse> list(@RequestParam(required = false) String role) {
        return service.list(role);
    }

    @Operation(summary = "Change a staff account's status", description = "Writes an audit_log entry as a side effect of every status change.")
    @PutMapping("/{staffId}/status")
    public StaffResponse updateStatus(@PathVariable UUID staffId, @Valid @RequestBody StaffStatusRequest req) {
        return service.updateStatus(staffId, req);
    }
}
