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
 * <h2>Session 45: this controller finally has a caller, and it is the point of the design</h2>
 * The TODO below said to delete this once nothing needed it. The opposite happened — it turned
 * out to be exactly the right shape for the gap that mattered most.
 *
 * <p>Until Session 45 <b>nobody could open a support ticket at all</b>. The console could list,
 * triage, reply to and close tickets; the SLA clock ran; five console pages were built. And no
 * path existed by which a ticket could come into existence, in any of the three repos. A
 * complete support desk with the phone line unplugged.
 *
 * <p>The obvious fix — let customers and owners POST here directly — is impossible and it is
 * worth understanding why, because the constraint is what produced the design.
 * {@link com.bmp.admin.security.AdminSecurityConfig} gives bmp-admin its own signing key and a
 * {@code bmp-admin} audience claim, so a customer or salon-owner JWT is <em>rejected by this
 * service by design</em>. That is a good property and not one to weaken for a feature.
 *
 * <p>So the user-facing door is {@code /api/v1/support} in <b>bmp-user</b>, which verifies the
 * user's JWT, derives who they are from it, and calls the {@code /my/**} endpoints below over
 * the internal service key. Which is precisely what a service-only ticket API is for.
 *
 * <h2>bmp-user says WHO; this service decides WHAT THEY MAY SEE</h2>
 * Every {@code /my/**} endpoint takes a caller identity and re-checks ownership against
 * {@code raised_by_id} / {@code salon_id} here, next to the data. bmp-user is trusted to
 * authenticate; it is not trusted to authorise. If it were, a bug in one service's parameter
 * handling would become a cross-tenant read of every support ticket on the platform.
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

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Session 45 — the requester's own view, called by bmp-user on behalf of a signed-in user.
    // ═══════════════════════════════════════════════════════════════════════════════════════
    //
    // These return MyTicketResponse, never TicketResponse. The difference is not cosmetic:
    // MyTicketResponse omits assignedStaffId (internal routing) and its thread CANNOT contain
    // internal notes — the mapping filters them at the source rather than relying on a flag some
    // future call site forgets to pass. See SupportTicketService.toMyTicket.
    //
    // `callerId` and `callerSalonId` are query parameters rather than body fields because two of
    // the three are GETs. They come from bmp-user's verified JWT — never from an end user — and
    // are re-checked against the ticket here regardless.

    @Operation(summary = "Raise a ticket on behalf of a signed-in user",
            description = "Identity fields are supplied by the calling service from a verified JWT. "
                    + "Creates the ticket AND its first message from `description`.")
    @PostMapping("/my")
    public ResponseEntity<MyTicketResponse> raise(@Valid @RequestBody RaiseTicketRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.raise(req));
    }

    @Operation(summary = "List the tickets a user may see",
            description = "Their own, plus — when callerSalonId is given — everything raised for "
                    + "that salon, so an owner sees what their managers reported.")
    @GetMapping("/my")
    public List<MyTicketResponse> listMine(@RequestParam UUID callerId,
                                            @RequestParam(required = false) UUID callerSalonId) {
        return service.listMine(callerId, callerSalonId);
    }

    @Operation(summary = "Read one ticket and its thread, if this user may see it",
            description = "404 rather than 403 when they may not — a 403 confirms the ticket exists.")
    @GetMapping("/my/{ticketId}")
    public MyTicketResponse getMine(@PathVariable UUID ticketId,
                                     @RequestParam UUID callerId,
                                     @RequestParam(required = false) UUID callerSalonId) {
        return service.getMine(ticketId, callerId, callerSalonId);
    }

    @Operation(summary = "Reply to a ticket as the user",
            description = "Reopens a ticket that was waiting on the user or already resolved, so "
                    + "their reply returns to the queue agents actually watch. A CLOSED ticket is "
                    + "refused with 409 — that is a finished conversation and a new problem "
                    + "deserves its own SLA clock.")
    @PostMapping("/my/{ticketId}/messages")
    public ResponseEntity<MyTicketResponse> replyAsUser(@PathVariable UUID ticketId,
                                                         @Valid @RequestBody UserReplyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.replyAsUser(ticketId, req.callerId(), req.callerSalonId(),
                        req.senderType(), req.body()));
    }
}
