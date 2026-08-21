package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminDtos.*;
import com.bmp.admin.services.BmpStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * BMP-29: admin_schema.bmp_staff CRUD. password_hash never appears in any response.
 *
 * <h2>⚠️ DEPRECATED, AND IT WAS THE WORST HOLE IN THE PLATFORM</h2>
 * This controller sits at {@code /api/v1/staff} — <b>outside</b> {@code AdminSecurityConfig}'s
 * {@code /api/v1/admin/**} matcher. It therefore fell through to the shared chain, which
 * accepts any valid JWT, and it had no {@code @PreAuthorize} on any method.
 *
 * <p><b>The consequence: any logged-in CUSTOMER could POST here and create themselves a BMP
 * staff account.</b> Not a privilege-escalation chain — a single call. Found in the Session 29
 * access audit; the same shape as the SupportTicketController hole found in Session 20, which
 * is the tell that "controller outside the admin matcher" is a category, not an incident.
 *
 * <p><b>Superseded by {@link StaffAdminController}</b> ({@code /api/v1/admin/staff}), which the
 * console actually uses and which enforces {@code staff:manage} inside
 * {@code StaffAdminService}. Nothing in either frontend calls this class.
 *
 * <p>Now locked to {@code ROLE_SERVICE} — no human token of any kind reaches it — as the
 * smallest change that closes the hole without deleting code someone may be mid-way through
 * using. <b>It should be deleted outright</b>; a second, unused door into staff records is
 * pure liability. Tracked in {@code docs/PENDING_WORK.md} §4.
 *
 * @deprecated use {@link StaffAdminController} at {@code /api/v1/admin/staff}.
 */
@Deprecated(forRemoval = true)
@Tag(name = "BMP Staff (DEPRECATED)", description = "Superseded by /api/v1/admin/staff. Locked to internal service calls only; scheduled for removal.")
@RestController
@RequestMapping("/api/v1/staff")
@PreAuthorize("hasRole('SERVICE')")
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
