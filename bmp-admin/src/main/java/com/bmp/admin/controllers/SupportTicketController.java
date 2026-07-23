package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminDtos.*;
import com.bmp.admin.services.SupportTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-29: admin_schema.support_ticket + support_message CRUD. */
@Tag(name = "Support Tickets", description = "support_ticket + support_message CRUD.")
@RestController
@RequestMapping("/api/v1/support-tickets")
public class SupportTicketController {

    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    @Operation(summary = "Open a support ticket")
    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a ticket by id", description = "Optionally include its message thread.")
    @GetMapping("/{ticketId}")
    public TicketResponse getById(@PathVariable UUID ticketId,
                                   @RequestParam(defaultValue = "false") boolean includeMessages) {
        return service.getById(ticketId, includeMessages);
    }

    @Operation(summary = "List tickets", description = "Optionally filter by status and/or assigned staff member.")
    @GetMapping
    public List<TicketResponse> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) UUID assignedStaffId) {
        return service.list(status, assignedStaffId);
    }

    @Operation(summary = "Update a ticket's status/assignment")
    @PutMapping("/{ticketId}")
    public TicketResponse update(@PathVariable UUID ticketId, @RequestBody UpdateTicketRequest req) {
        return service.update(ticketId, req);
    }

    @Operation(summary = "Add a message to a ticket's thread")
    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<MessageResponse> addMessage(@PathVariable UUID ticketId,
                                                       @Valid @RequestBody CreateMessageRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addMessage(ticketId, req));
    }
}
