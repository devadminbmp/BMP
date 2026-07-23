package com.bmp.notification.controllers;

import com.bmp.notification.dto.NotificationDtos.LogRequest;
import com.bmp.notification.dto.NotificationDtos.LogResponse;
import com.bmp.notification.dto.NotificationDtos.StatsResponse;
import com.bmp.notification.services.NotificationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-30: POST/GET notification_log — see NotificationLogService for scope notes. */
@Tag(name = "Notifications", description = "notification_log CRUD. Real sends happen via NotificationDispatcher (Kafka consumer on bmp.events), not through this controller directly — this is the record-keeping API.")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationLogController {

    private final NotificationLogService service;

    public NotificationLogController(NotificationLogService service) {
        this.service = service;
    }

    @Operation(summary = "Log a notification", description = "Status starts as \"queued\"; NotificationDispatcher flips it to sent/failed after attempting real delivery.")
    @PostMapping("/log")
    public ResponseEntity<LogResponse> log(@Valid @RequestBody LogRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.log(req));
    }

    @Operation(summary = "Get a notification log entry by id")
    @GetMapping("/log/{id}")
    public LogResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "List notification log entries", description = "Optionally filter by recipient and/or channel (email/sms/whatsapp/push). Loads every matching row into memory — fine for admin/debug use, use the paginated /recipient/{id} endpoint for a real recipient inbox.")
    @GetMapping("/log")
    public List<LogResponse> list(@RequestParam(required = false) UUID recipientUserId,
                                   @RequestParam(required = false) String channel) {
        return service.list(recipientUserId, channel);
    }

    @Operation(summary = "Paginated notification history for a recipient", description = "Session 7 addition, ported from Shivam's parallel BMP-30 work. Ordered newest-first via Pageable sort.")
    @GetMapping("/recipient/{recipientUserId}")
    public Page<LogResponse> getByRecipient(@PathVariable UUID recipientUserId, Pageable pageable) {
        return service.getByRecipient(recipientUserId, pageable);
    }

    @Operation(summary = "Pending (queued or sent, not yet delivered/failed) notifications for a recipient", description = "Session 7 addition — surfaces \"still in flight\" sends.")
    @GetMapping("/recipient/{recipientUserId}/pending")
    public Page<LogResponse> getPending(@PathVariable UUID recipientUserId, Pageable pageable) {
        return service.getPendingNotifications(recipientUserId, pageable);
    }

    @Operation(summary = "Notification counts by status", description = "Session 7 addition — queued/sent/delivered/failed counts across all recipients, for an ops dashboard.")
    @GetMapping("/stats")
    public StatsResponse stats() {
        return service.getStats();
    }

    @Operation(summary = "Mark a notification as delivered", description = "Session 7 addition. No provider delivery-receipt webhook calls this yet (Phase 3) — exists so one can be wired up without touching the service layer again.")
    @PutMapping("/log/{id}/delivered")
    public LogResponse markDelivered(@PathVariable UUID id) {
        return service.markDelivered(id);
    }
}
