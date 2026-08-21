package com.bmp.admin.controllers;

import com.bmp.admin.entities.SupportMessage;
import com.bmp.admin.entities.SupportTicket;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.SupportMessageRepository;
import com.bmp.admin.repositories.SupportTicketRepository;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The support desk, for the console.
 *
 * <p>Mounted under {@code /api/v1/admin/**} so it runs through the STAFF filter chain. The
 * older {@code /api/v1/support-tickets} controller is service-only now — see its class comment
 * for why that mattered.
 *
 * <h2>The one thing this must never get wrong</h2>
 * Sending an internal note to the customer. It's the classic support-tool disaster, and it's
 * always caused by an ambiguous mode. So {@code internalNote} is an explicit field with a safe
 * default, the SLA clock only stops on a customer-visible reply, and the console renders the two
 * kinds of message in visibly different colours with different button text.
 */
@Tag(name = "Support desk", description = "Ticket queue and conversation for the console. Internal notes are never visible to customers.")
@RestController
@RequestMapping("/api/v1/admin/support")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
public class SupportDeskController {

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final BmpStaffRepository staffRepo;
    private final AuditLogService audit;

    public SupportDeskController(SupportTicketRepository tickets, SupportMessageRepository messages,
                                 BmpStaffRepository staffRepo, AuditLogService audit) {
        this.tickets = tickets;
        this.messages = messages;
        this.staffRepo = staffRepo;
        this.audit = audit;
    }

    public record TicketResponse(
        UUID id, String ticketRef, String subject, String category, String status, String priority,
        String requesterName, String requesterEmailMasked, String requesterPhoneMasked,
        String assignedToName, UUID bookingId, UUID salonId,
        Instant firstResponseDueAt, Instant firstRespondedAt, Instant createdAt, Instant updatedAt
    ) {}

    public record MessageResponse(
        UUID id, String authorType, String authorName, String body, boolean internalNote, Instant createdAt
    ) {}

    public record ReplyRequest(@NotBlank String body, boolean internalNote) {}

    public record UpdateTicketRequest(String status, String priority, Boolean assignToMe) {}

    @Operation(
        summary = "The queue",
        description = "Filtered by status and/or assignment. Ordered breached-first, then by priority, then oldest — the order an agent should actually work them in, rather than by creation date.")
    @GetMapping("/tickets")
    public List<TicketResponse> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) Boolean assignedToMe,
                                      @RequestParam(required = false) String q,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        return tickets.findAll().stream()
                .filter(t -> status == null || status.equalsIgnoreCase(t.getStatus()))
                .filter(t -> !Boolean.TRUE.equals(assignedToMe)
                        || caller.staffId().equals(t.getAssignedStaffId()))
                .filter(t -> q == null || q.isBlank()
                        || t.getSubject().toLowerCase().contains(q.toLowerCase())
                        || t.getTicketRef().toLowerCase().contains(q.toLowerCase()))
                .sorted(queueOrder())
                .map(this::toResponse)
                .toList();
    }

    @Operation(summary = "The conversation", description = "Oldest first. Internal notes are flagged so the console can render them unmistakably.")
    @GetMapping("/tickets/{ticketId}/messages")
    public List<MessageResponse> messages(@PathVariable UUID ticketId) {
        return messages.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(m -> new MessageResponse(
                        m.getId(), m.getSenderType(), staffName(m.getSenderId()),
                        m.getMessageText(), m.isInternalNote(), m.getCreatedAt()))
                .toList();
    }

    @Operation(
        summary = "Reply, or add an internal note",
        description = "Only a customer-visible reply stops the first-response SLA clock. An internal note is not a response to the customer, and treating it as one would let a team quietly 'meet' an SLA while the customer heard nothing.")
    @PostMapping("/tickets/{ticketId}/messages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    @Transactional
    public ResponseEntity<MessageResponse> reply(@PathVariable UUID ticketId,
                                                  @Valid @RequestBody ReplyRequest req,
                                                  @AuthenticationPrincipal StaffPrincipal caller) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));

        SupportMessage saved = messages.save(new SupportMessage(
                ticketId, "bmp_staff", caller.staffId(), req.body(), null, req.internalNote()));

        if (!req.internalNote() && ticket.getFirstRespondedAt() == null) {
            ticket.setFirstRespondedAt(Instant.now());
            ticket.touch();
            tickets.save(ticket);
        }

        // TODO(notification): actually send a customer-visible reply by email. Right now it is
        // recorded but never delivered — flagged loudly because an agent believing they have
        // replied when the customer heard nothing is worse than no reply feature at all.

        audit.record("bmp_staff", caller.staffId(),
                req.internalNote() ? "TICKET_NOTE_ADDED" : "TICKET_REPLIED",
                "support_ticket", ticketId, Map.of("ref", ticket.getTicketRef()),
                null, caller.email(), caller.role(), null);

        return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse(
                saved.getId(), saved.getSenderType(), staffName(saved.getSenderId()),
                saved.getMessageText(), saved.isInternalNote(), saved.getCreatedAt()));
    }

    @Operation(summary = "Assign, re-prioritise or resolve")
    @PatchMapping("/tickets/{ticketId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    @Transactional
    public TicketResponse update(@PathVariable UUID ticketId,
                                  @RequestBody UpdateTicketRequest req,
                                  @AuthenticationPrincipal StaffPrincipal caller) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));

        if (req.status() != null) ticket.setStatus(req.status());
        if (req.priority() != null) ticket.setPriority(req.priority());
        if (Boolean.TRUE.equals(req.assignToMe())) ticket.setAssignedStaffId(caller.staffId());
        if ("resolved".equals(req.status())) ticket.setResolvedAt(Instant.now());
        ticket.touch();
        tickets.save(ticket);

        audit.record("bmp_staff", caller.staffId(), "TICKET_UPDATED", "support_ticket", ticketId,
                Map.of("status", String.valueOf(req.status()), "priority", String.valueOf(req.priority())),
                null, caller.email(), caller.role(), null);

        return toResponse(ticket);
    }

    /** Breached first, then priority, then oldest — how a queue should actually be worked. */
    private Comparator<SupportTicket> queueOrder() {
        Map<String, Integer> rank = Map.of("urgent", 0, "high", 1, "medium", 2, "low", 3);
        return Comparator
                .comparing((SupportTicket t) -> !isBreaching(t))
                .thenComparing(t -> rank.getOrDefault(t.getPriority(), 9))
                .thenComparing(SupportTicket::getCreatedAt);
    }

    private boolean isBreaching(SupportTicket t) {
        return t.getFirstRespondedAt() == null
                && t.getFirstResponseDueAt() != null
                && t.getFirstResponseDueAt().isBefore(Instant.now());
    }

    private String staffName(UUID staffId) {
        if (staffId == null) return null;
        return staffRepo.findById(staffId).map(s -> s.getName()).orElse(null);
    }

    private TicketResponse toResponse(SupportTicket t) {
        return new TicketResponse(
                t.getId(), t.getTicketRef(), t.getSubject(), t.getCategory(), t.getStatus(),
                t.getPriority(),
                // TODO(bmp-user): resolve the requester's name from raised_by_id. Masked contact
                // fields come straight from the ticket, which only has them for account-less
                // requesters — the rest go through the audited reveal on the Users screen.
                null, t.getRequesterEmail(), t.getRequesterPhone(),
                staffName(t.getAssignedStaffId()), t.getBookingId(), t.getSalonId(),
                t.getFirstResponseDueAt(), t.getFirstRespondedAt(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
