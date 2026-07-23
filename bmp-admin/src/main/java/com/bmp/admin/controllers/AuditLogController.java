package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminDtos.AuditLogResponse;
import com.bmp.admin.services.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** BMP-29 / BMP-3: READ-ONLY. No POST/PUT/DELETE exposed — rows only ever inserted as a side-effect (see AuditLogService). */
@Tag(name = "Audit Log", description = "Read-only. Rows are only ever inserted as a side effect of another action (e.g. a staff status change) — there is no direct write endpoint.")
@RestController
@RequestMapping("/api/v1/audit-log")
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
