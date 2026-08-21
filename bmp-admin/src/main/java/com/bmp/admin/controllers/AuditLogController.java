package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminDtos.AuditLogResponse;
import com.bmp.admin.services.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * BMP-29 / BMP-3: READ-ONLY. No POST/PUT/DELETE exposed — rows are only ever inserted as a
 * side effect of another action (see AuditLogService).
 *
 * <h2>⚠️ DEPRECATED — and read-only did not make it safe</h2>
 * Like {@link BmpStaffController}, this sits at {@code /api/v1/audit-log}, <b>outside</b>
 * {@code AdminSecurityConfig}'s {@code /api/v1/admin/**} matcher, and had no
 * {@code @PreAuthorize}. It fell through to the shared chain, which accepts any valid JWT — so
 * <b>any logged-in customer could read the entire audit log</b>.
 *
 * <p>"It's only a read" understates it. The audit log is the record of which staff member
 * looked at which customer's phone number and why: it contains staff names, customer ids, and
 * the justifications typed when PII was revealed. It is arguably the most sensitive table in
 * the platform, and it is precisely the table an attacker reads to find out what is worth
 * attacking and who has access.
 *
 * <p>Superseded by {@code GET /api/v1/admin/audit} on {@link ConsoleController}, which the
 * console uses and which requires {@code audit:view} (super, ops, finance). Locked to
 * {@code ROLE_SERVICE} here; <b>should be deleted</b> — see {@code docs/PENDING_WORK.md} §4.
 *
 * @deprecated use {@code GET /api/v1/admin/audit}.
 */
@Deprecated(forRemoval = true)
@Tag(name = "Audit Log (DEPRECATED)", description = "Superseded by /api/v1/admin/audit. Locked to internal service calls only; scheduled for removal.")
@RestController
@RequestMapping("/api/v1/audit-log")
@PreAuthorize("hasRole('SERVICE')")
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @Operation(summary = "Look up audit log entries for one entity", description = "e.g. entityType=bmp_staff, entityId=<staffId> to see every status change made to that staff account.")
    @GetMapping
    public List<AuditLogResponse> find(@RequestParam String entityType, @RequestParam UUID entityId) {
        return service.find(entityType, entityId);
    }
}
