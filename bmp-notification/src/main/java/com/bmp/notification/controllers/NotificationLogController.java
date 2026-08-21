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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * BMP-30: POST/GET notification_log — see NotificationLogService for scope notes.
 *
 * <h2>Session 29: SERVICE-ONLY, at class level</h2>
 * bmp-notification declared no {@code public-paths}, so the {@code /**} code default applied
 * and all seven endpoints were reachable with no token. That included
 * {@code GET /notifications/log} — the whole table — and
 * {@code GET /notifications/recipient/{userId}}, one person's entire message history.
 *
 * <p><b>This log is worse to leak than most of the tables it describes.</b> It records who was
 * sent what, at which phone number and email address, and when: contact details joined to
 * activity. A booking row tells you someone had a haircut; this tells you their number and that
 * they were reminded about it on Tuesday.
 *
 * <p>Written to by {@code NotificationDispatcher} (the Kafka consumer) and read by staff tooling
 * through bmp-admin, never by a browser or the app. There is no end-user role that belongs
 * here at all, which is why the rule is at the class and not per-method — a method added later
 * inherits the protection rather than needing to remember it.
 */
@Tag(name = "Notifications", description = "notification_log CRUD. SERVICE role only — contains contact details joined to activity. Real sends happen via NotificationDispatcher (Kafka consumer on bmp.events).")
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasRole('SERVICE')")
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
