package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminDtos.*;
import com.bmp.admin.services.SupportTicketService;
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
 * BMP-29: admin_schema.support_ticket + support_message CRUD.
 *
 * <h2>⚠️ Session 21: this is now SERVICE-ONLY, and that was a real hole</h2>
 * This controller sits at {@code /api/v1/support-tickets} — <b>outside</b> the
 * {@code /api/v1/admin/**} matcher that {@link com.bmp.admin.security.AdminSecurityConfig}
 * guards. It therefore fell through to bmp-common's shared chain, which accepts a CUSTOMER
 * token. Any logged-in customer could have listed, read and edited every support ticket on the
 * platform, including internal notes about other customers.
 *
 * <p>It predates the console's own auth and was never reachable from the app, which is why
 * nobody noticed. Locking it to {@code ROLE_SERVICE} keeps it usable for the path it was built
 * for — another service raising a ticket automatically — while closing it to end users.
 *
 * <p>Staff use {@link SupportDeskController} at {@code /api/v1/admin/support/**}, which runs
 * through the staff filter chain and audits everything.
 *
 * <p>TODO: fold the remaining useful bits into SupportDeskController and delete this, once the
 * console no longer needs a create-ticket path that predates it.
 */
@Tag(name = "Support Tickets (internal)", description = "Service-to-service ticket creation. Staff use /api/v1/admin/support/** instead — see SupportDeskController.")
@RestController
@RequestMapping("/api/v1/support-tickets")
@PreAuthorize("hasRole('SERVICE')")
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
