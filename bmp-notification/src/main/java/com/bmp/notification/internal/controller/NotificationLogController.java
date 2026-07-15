package com.bmp.notification.internal.controller;

import com.bmp.notification.internal.dto.NotificationDtos.LogRequest;
import com.bmp.notification.internal.dto.NotificationDtos.LogResponse;
import com.bmp.notification.internal.service.NotificationLogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-30: POST/GET notification_log — see NotificationLogService for scope notes. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationLogController {

    private final NotificationLogService service;

    public NotificationLogController(NotificationLogService service) {
        this.service = service;
    }

    @PostMapping("/log")
    public ResponseEntity<LogResponse> log(@Valid @RequestBody LogRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.log(req));
    }

    @GetMapping("/log/{id}")
    public LogResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/log")
    public List<LogResponse> list(@RequestParam(required = false) UUID recipientUserId,
                                   @RequestParam(required = false) String channel) {
        return service.list(recipientUserId, channel);
    }
}
