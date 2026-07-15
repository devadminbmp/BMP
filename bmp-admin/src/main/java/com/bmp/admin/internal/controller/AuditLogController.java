package com.bmp.admin.internal.controller;

import com.bmp.admin.internal.dto.AdminDtos.AuditLogResponse;
import com.bmp.admin.internal.service.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** BMP-29 / BMP-3: READ-ONLY. No POST/PUT/DELETE exposed — rows only ever inserted as a side-effect (see AuditLogService). */
@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditLogResponse> find(@RequestParam String entityType, @RequestParam UUID entityId) {
        return service.find(entityType, entityId);
    }
}
