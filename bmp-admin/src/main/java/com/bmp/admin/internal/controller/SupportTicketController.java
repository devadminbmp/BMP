package com.bmp.admin.internal.controller;

import com.bmp.admin.internal.dto.AdminDtos.*;
import com.bmp.admin.internal.service.SupportTicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-29: admin_schema.support_ticket + support_message CRUD. */
@RestController
@RequestMapping("/api/v1/support-tickets")
public class SupportTicketController {

    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{ticketId}")
    public TicketResponse getById(@PathVariable UUID ticketId,
                                   @RequestParam(defaultValue = "false") boolean includeMessages) {
        return service.getById(ticketId, includeMessages);
    }

    @GetMapping
    public List<TicketResponse> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) UUID assignedStaffId) {
        return service.list(status, assignedStaffId);
    }

    @PutMapping("/{ticketId}")
    public TicketResponse update(@PathVariable UUID ticketId, @RequestBody UpdateTicketRequest req) {
        return service.update(ticketId, req);
    }

    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<MessageResponse> addMessage(@PathVariable UUID ticketId,
                                                       @Valid @RequestBody CreateMessageRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addMessage(ticketId, req));
    }
}
