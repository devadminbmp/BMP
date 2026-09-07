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
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT','FINANCE_ADMIN','READ_ONLY')")
public class SupportDeskController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SupportDeskController.class);

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final BmpStaffRepository staffRepo;
    private final AuditLogService audit;
    /** V008 (Session 48) — so a reply actually reaches the person who raised the ticket. */
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    /** Session 56 — so the queue shows a person's name, not a bare UUID. See requesterName. */
    private final com.bmp.admin.client.UserServiceClient users;
    /** Session 64 — supplies the one-line explanation of a derived priority. */
    private final com.bmp.admin.services.TicketPriorityPolicy priorityPolicy;
    /** Session 64 (V013) — the append-only record of who held a ticket and why it moved. */
    private final com.bmp.admin.repositories.TicketAssignmentRepository assignments;
    /** Session 64 — derives what the requester is told. See TicketHandlingState. */
    private final com.bmp.admin.services.TicketHandlingState handling;
    /** Session 64 — finally reachable. See the escalate() endpoint for why that sentence exists. */
    private final com.bmp.admin.services.TicketEscalationService escalation;

    public SupportDeskController(SupportTicketRepository tickets, SupportMessageRepository messages,
                                 BmpStaffRepository staffRepo, AuditLogService audit,
                                 com.bmp.common.outbox.OutboxPublisher outbox,
                                 com.bmp.admin.client.UserServiceClient users,
                                 com.bmp.admin.services.TicketPriorityPolicy priorityPolicy,
                                 com.bmp.admin.repositories.TicketAssignmentRepository assignments,
                                 com.bmp.admin.services.TicketHandlingState handling,
                                 com.bmp.admin.services.TicketEscalationService escalation) {
        this.tickets = tickets;
        this.messages = messages;
        this.staffRepo = staffRepo;
        this.audit = audit;
        this.outbox = outbox;
        this.users = users;
        this.priorityPolicy = priorityPolicy;
        this.assignments = assignments;
        this.handling = handling;
        this.escalation = escalation;
    }

    /**
     * The name behind {@code raised_by_id}, or null. Session 56.
     *
     * <h2>Why it was a TODO for so long</h2>
     * The queue rendered a UUID, so an agent picking up a ticket had no idea who they were
     * talking to until they opened it and cross-referenced. The comment said "resolve the
     * requester's name from raised_by_id" and stayed put because it needs a call into bmp-user
     * that this controller had no client for.
     *
     * <h2>Only for customers</h2>
     * {@code raised_by_type} can also be {@code salon} or {@code staff}, whose ids are not bmp-user
     * ids — looking one of those up would 404 every time and log noise on every page render. So it
     * resolves only what it can, and null renders as the ticket reference, which is what agents
     * quote to each other anyway.
     *
     * <p>Never fatal. A support queue that fails to load because a name lookup timed out is worse
     * than a queue with one blank column, and the queue is what somebody is staring at during an
     * incident.
     */
    private String requesterName(SupportTicket t) {
        if (t.getRaisedById() == null || !"customer".equalsIgnoreCase(t.getRaisedByType())) {
            return null;
        }
        try {
            var body = users.getUserById(t.getRaisedById()).getBody();
            return body == null ? null : body.name();
        } catch (Exception e) {
            log.debug("Could not resolve a name for ticket {} ({})", t.getTicketRef(), e.toString());
            return null;
        }
    }

    /**
     * @param raisedById  Session 45. The account that opened the ticket, so the console can link
     *                    to the Users screen and identify an in-app requester. Before this,
     *                    in-app tickets were ANONYMOUS to an agent: requesterName is null (see
     *                    toResponse's TODO) and the contact columns are only populated for
     *                    account-less phone-ins. That was tolerable while no in-app tickets
     *                    existed; from this session they are the majority.
     */
    /**
     * @param requesterKind  WHO is asking — 'customer', 'salon_owner', 'manager', 'bmp_staff'.
     *                       Session 64.
     *
     *   This is `raised_by_type`, which has existed since V002, has been written correctly on every
     *   ticket since, and until now was rendered NOWHERE in the console. An agent opening the queue
     *   saw a customer's question about a haircut and a salon reporting it could not trade at all as
     *   two identical grey rows.
     *
     *   That single omission also disabled `priority`: you cannot rank by who is blocked when who is
     *   blocked is invisible. Both fields existed; neither worked, because one of them was never
     *   shown. A column that is written and never read is a promise the UI never kept.
     *
     * @param salonName      the salon this concerns, snapshotted (V013). An agent triaging needs
     *                       "The Grooming Room", not a UUID to paste into another screen.
     * @param priorityAuto   false once a person has set the priority; the console shows that so an
     *                       agent knows whether they are overriding a rule or a colleague.
     * @param priorityReason one sentence explaining the derived rank. Agents distrust invisible
     *                       ordering — rightly — and route around it.
     * @param tier           the support tier currently holding it, 1..4.
     */
    public record TicketResponse(
        UUID id, String ticketRef, String subject, String category, String status, String priority,
        String requesterName, String requesterEmailMasked, String requesterPhoneMasked,
        UUID raisedById,
        String requesterKind, String salonName,
        boolean priorityAuto, String priorityReason,
        UUID assignedStaffId, String assignedToName, short tier, int escalationCount,
        UUID bookingId, UUID salonId,
        String handlingDesk, String handlingDeskLabel, String deskTransferReason,
        String handlingState, String handlingLabel,
        Instant firstResponseDueAt, Instant firstRespondedAt, Instant createdAt, Instant updatedAt
    ) {}

    /** Who is holding what, for the oversight screen. Session 64. */
    public record AgentWorkload(
        UUID staffId, String name, String role, short tier, boolean acceptingTickets,
        int openCount, int urgentCount, int breachedCount
    ) {}

    /** One movement of a ticket between people. */
    public record AssignmentEntry(
        String action, String fromName, String toName, String actorName, String reason, Instant at
    ) {}

    public record AssignRequest(UUID toStaffId, String reason) {}

    /**
     * Hand a ticket to a different FUNCTION. Session 64.
     *
     * @param toDesk 'support' | 'finance' | 'ops' | 'moderation'
     * @param reason required, and at least ten characters — same bar as escalation, for the same
     *               reason: the receiving desk reads this instead of the whole thread, and
     *               "transferred" with no explanation makes them start from scratch.
     */
    public record TransferRequest(@NotBlank String toDesk, @NotBlank String reason) {}

    /** Move a ticket UP the seniority ladder. See TicketEscalationService for the rules. */
    public record EscalateRequest(@NotBlank String reason) {}

    public record MessageResponse(
        UUID id, String authorType, String authorName, String body, boolean internalNote, Instant createdAt
    ) {}

    public record ReplyRequest(@NotBlank String body, boolean internalNote) {}

    public record UpdateTicketRequest(String status, String priority, Boolean assignToMe) {}

    /**
     * A ticket an AGENT opens on someone else's behalf. Session 45.
     *
     * @param requesterName  who called. Free text on purpose — the whole point of this endpoint
     *                       is the caller who has no account, or whose account we can't find
     *                       while they're on the phone.
     * @param requesterEmail how to reply. At least one of email or phone is required; a ticket
     *                       nobody can be answered on is a note to self, not a ticket.
     */
    public record CreateOnBehalfRequest(
        @NotBlank String category, @NotBlank String subject, @NotBlank String description,
        String requesterName, String requesterEmail, String requesterPhone,
        UUID raisedById, UUID salonId, UUID bookingId, String priority
    ) {}

    @Operation(
        summary = "The queue",
        description = "Filtered by status and/or assignment. Ordered breached-first, then by priority, then oldest — the order an agent should actually work them in, rather than by creation date.")
    @GetMapping("/tickets")
    public List<TicketResponse> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) Boolean assignedToMe,
                                      @RequestParam(required = false) String q,
                                      /*
                                       * WHICH DESK. Session 64, added immediately after the
                                       * transfer feature — because without it the transfer moved a
                                       * label and nothing else.
                                       *
                                       * A ticket handed to finance stayed in the support agent's
                                       * queue looking exactly as before, and finance had no way to
                                       * ask "what is on my desk". The handover was recorded,
                                       * audited, and operationally invisible to both sides.
                                       *
                                       * The same failure this codebase keeps repeating: the move
                                       * was built, and nothing let anyone SEE its result.
                                       */
                                      @RequestParam(required = false) String desk,
                                      // Session 45: salons can now raise tickets themselves, so
                                      // "show me everything from this salon" became a real
                                      // question — a salon that calls about three issues at once
                                      // is one conversation, not three unrelated queue entries.
                                      @RequestParam(required = false) UUID salonId,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        return tickets.findAll().stream()
                .filter(t -> status == null || status.equalsIgnoreCase(t.getStatus()))
                .filter(t -> desk == null || desk.equalsIgnoreCase(t.getHandlingDesk()))
                .filter(t -> salonId == null || salonId.equals(t.getSalonId()))
                .filter(t -> !Boolean.TRUE.equals(assignedToMe)
                        || caller.staffId().equals(t.getAssignedStaffId()))
                .filter(t -> q == null || q.isBlank()
                        || t.getSubject().toLowerCase().contains(q.toLowerCase())
                        || t.getTicketRef().toLowerCase().contains(q.toLowerCase()))
                .sorted(queueOrder())
                .map(this::toResponse)
                .toList();
    }

    @Operation(
        summary = "Open a ticket on behalf of someone who contacted us another way",
        description = "For phone calls, walk-ins and emails. Until Session 45 nothing anywhere "
                + "could create a ticket, so a caller had nowhere to be recorded and the queue "
                + "only ever reflected the channel we hadn't built yet.")
    @PostMapping("/tickets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @Transactional
    public ResponseEntity<TicketResponse> createOnBehalf(@Valid @RequestBody CreateOnBehalfRequest req,
                                                          @AuthenticationPrincipal StaffPrincipal caller) {
        // A ticket we cannot reply to is a note to self. Better to refuse it than to let an
        // agent finish the form and discover at resolution time that there is no way to answer.
        if (isBlank(req.requesterEmail()) && isBlank(req.requesterPhone()) && req.raisedById() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "NO_CONTACT: give an email, a phone number, or link an existing account — "
                    + "otherwise there is no way to reply to this ticket.");
        }

        String ref = "TCK-" + Instant.now().atZone(java.time.ZoneOffset.UTC).getYear() + "-"
                + String.format("%05d", tickets.countByTicketRefStartingWith(
                        "TCK-" + Instant.now().atZone(java.time.ZoneOffset.UTC).getYear() + "-") + 1);

        SupportTicket t = new SupportTicket(ref,
                // raised_by_type records WHO THE TICKET IS ABOUT, not who typed it. A phone-in
                // customer's ticket is a customer ticket even though staff created it — recording
                // it as bmp_staff would corrupt every "tickets by requester type" report and make
                // the queue look like the team talking to itself.
                //
                // Always "customer" here rather than conditional on raisedById: this endpoint is
                // for people who contacted us by phone, email or in person, and every one of them
                // is a customer or a salon whether or not we managed to match them to an account.
                "customer",
                // Falls back to the agent's own id only so the NOT NULL column has a value; the
                // requester name/email/phone below are what an agent actually replies to.
                req.raisedById() != null ? req.raisedById() : caller.staffId(),
                req.bookingId(), req.category(), req.subject(),
                "open", req.priority() != null ? req.priority() : "medium", caller.staffId(), null);

        t.setSalonId(req.salonId());
        t.setRequesterEmail(req.requesterEmail());
        t.setRequesterPhone(req.requesterPhone());
        // Already talking to them, so the first response has happened. Leaving the SLA clock
        // running would show a breach for a conversation that is actively in progress, and an
        // SLA metric that fires on tickets being handled well is one people learn to ignore.
        t.setFirstRespondedAt(Instant.now());
        t = tickets.save(t);

        // The agent's summary of what was said becomes message #1, attributed to staff — because
        // staff wrote it. Attributing it to the customer would put words in their mouth in a
        // record that may later be disputed.
        messages.save(new SupportMessage(t.getId(), "bmp_staff", caller.staffId(),
                req.description(), null, false));

        audit.record("bmp_staff", caller.staffId(), "TICKET_CREATED_ON_BEHALF",
                "support_ticket", t.getId(),
                Map.of("ref", t.getTicketRef(), "requester",
                        req.requesterName() == null ? "unknown" : req.requesterName()),
                null, caller.email(), caller.role(), null);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(t));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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

        /*
         * ═══════════════════════════════════════════════════════════════════════════════════════
         * TELL THE PERSON. Session 48 — this was the TODO that sat here for several sessions.
         * ═══════════════════════════════════════════════════════════════════════════════════════
         * The reply was recorded and never delivered. The console showed the conversation and the
         * agent believed they had answered; the customer heard nothing, so their ticket looked
         * ignored and the rational next move is to raise another one — or to stop using support.
         *
         * INTERNAL NOTES ARE NEVER SENT. A note is staff talking to each other about a customer,
         * and mailing it to them is the worst thing this feature could do. The check is here, at
         * the publish site, and SupportTicketReplied deliberately carries no "internal" flag —
         * so there is no way to build one that is internal-but-sent, and no flag for a future
         * reader to get backwards.
         *
         * Published to the outbox in the same transaction as the message row: either the reply
         * exists and the email is queued, or neither happened. Never a recorded reply with no
         * notification, which is the state this whole block exists to eliminate.
         */
        if (!req.internalNote()) {
            if (ticket.getRequesterEmail() == null || ticket.getRequesterEmail().isBlank()) {
                // Loud: the agent has answered into the void. They can still phone, but only if
                // they know. Silent here would recreate the exact bug being fixed.
                log.warn("Ticket {} was replied to but has NO requester email — the customer has "
                        + "NOT been told. Contact them another way.", ticket.getTicketRef());
            } else {
                outbox.publish(new com.bmp.common.events.SupportTicketReplied(
                        ticket.getId(), ticket.getTicketRef(), ticket.getSubject(),
                        req.body(), staffName(caller.staffId()),
                        ticket.getRequesterEmail(),
                        // V013 (Session 64) added requester_name, so the greeting can use it.
                        // Still nullable — a ticket raised before that column existed has none,
                        // and the email template falls back to a neutral greeting rather than
                        // inventing one.
                        null));
            }
        }

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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
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

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  OVERSIGHT — who is working what. Session 64.
    // ══════════════════════════════════════════════════════════════════════════════════════════
    /*
     * The gap this closes: an ops admin could see counts ("12 open, 3 unassigned") and their own
     * tickets, and nothing else. There was no way to answer "who is working this", "is anyone
     * working this", or "Priya is on leave, where did her queue go" — so reassignment happened by
     * asking people, and a ticket assigned to somebody who left simply stopped moving with nothing
     * anywhere reporting it.
     *
     * Deliberately admin-only. An agent seeing the whole floor's workload is at best a distraction
     * and at worst a league table; the queue view they already have is the right tool for the job
     * they actually do.
     */

    @Operation(summary = "Every ticket, with its owner — the oversight view",
               description = "Unfiltered by assignment, unlike /tickets. For admins deciding how work is spread, not for working the queue.")
    @GetMapping("/oversight/tickets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD')")
    public List<TicketResponse> oversightTickets(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String requesterKind,
                                                  @RequestParam(required = false) String priority,
                                                  @RequestParam(required = false) UUID assignedStaffId,
                                                  @RequestParam(required = false) String desk,
                                                  @RequestParam(required = false) Boolean unassigned) {
        return tickets.findAll().stream()
                .filter(t -> status == null || status.equalsIgnoreCase(t.getStatus()))
                .filter(t -> requesterKind == null || requesterKind.equalsIgnoreCase(t.getRaisedByType()))
                .filter(t -> priority == null || priority.equalsIgnoreCase(t.getPriority()))
                .filter(t -> desk == null || desk.equalsIgnoreCase(t.getHandlingDesk()))
                .filter(t -> assignedStaffId == null || assignedStaffId.equals(t.getAssignedStaffId()))
                .filter(t -> !Boolean.TRUE.equals(unassigned) || t.getAssignedStaffId() == null)
                .sorted(queueOrder())
                .map(this::toResponse)
                .toList();
    }

    @Operation(summary = "Workload per agent",
               description = "Open, urgent and SLA-breached counts for every staff member who can hold a ticket.")
    @GetMapping("/oversight/workload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD')")
    public List<AgentWorkload> workload() {
        List<SupportTicket> live = tickets.findAll().stream()
                .filter(t -> OPEN_STATES.contains(t.getStatus()))
                .toList();
        Instant now = Instant.now();

        return staffRepo.findAll().stream()
                // Tier 0 is off the escalation ladder (finance, read-only): they can read a queue
                // but never hold an item in one, so listing them would show permanent zeroes and
                // invite somebody to assign them work the assignment engine will not honour.
                .filter(st -> st.getTier() > 0)
                .filter(st -> "active".equalsIgnoreCase(st.getStatus()))
                .map(st -> {
                    List<SupportTicket> theirs = live.stream()
                            .filter(t -> st.getId().equals(t.getAssignedStaffId()))
                            .toList();
                    int breached = (int) theirs.stream()
                            .filter(t -> t.getFirstResponseDueAt() != null
                                    && t.getFirstRespondedAt() == null
                                    && t.getFirstResponseDueAt().isBefore(now))
                            .count();
                    int urgent = (int) theirs.stream()
                            .filter(t -> "urgent".equalsIgnoreCase(t.getPriority())
                                    || "high".equalsIgnoreCase(t.getPriority()))
                            .count();
                    return new AgentWorkload(st.getId(), st.getName(), st.getRole(), st.getTier(),
                            st.isAcceptingTickets(), theirs.size(), urgent, breached);
                })
                .sorted(Comparator.comparingInt(AgentWorkload::openCount).reversed())
                .toList();
    }

    @Operation(summary = "Assign, reassign or release a ticket",
               description = "toStaffId null releases it back to the pool. Every move is recorded in ticket_assignment.")
    @PostMapping("/oversight/tickets/{ticketId}/assign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD')")
    @Transactional
    public TicketResponse assign(@PathVariable UUID ticketId,
                                  @RequestBody AssignRequest req,
                                  @AuthenticationPrincipal StaffPrincipal caller) {
        SupportTicket t = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));

        UUID from = t.getAssignedStaffId();
        UUID to = req.toStaffId();

        /*
         * Refuse to assign to somebody who cannot hold the ticket, rather than accepting it and
         * letting it sit. A silent no-op here is the exact failure this whole endpoint exists to
         * make visible.
         */
        if (to != null) {
            var target = staffRepo.findById(to)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "NO_SUCH_STAFF"));
            if (!"active".equalsIgnoreCase(target.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That account is " + target.getStatus() + " and cannot be given tickets.");
            }
            if (target.getTier() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That role sits off the support ladder and cannot hold a ticket.");
            }
        }

        t.setAssignedStaffId(to);
        t.touch();
        tickets.save(t);

        String action = to == null ? com.bmp.admin.entities.TicketAssignment.RELEASE
                : from == null ? com.bmp.admin.entities.TicketAssignment.ASSIGN
                : com.bmp.admin.entities.TicketAssignment.REASSIGN;

        assignments.save(new com.bmp.admin.entities.TicketAssignment(
                ticketId, from, to, caller.staffId(), action, blankToNull(req.reason())));

        audit.record("bmp_staff", caller.staffId(), "TICKET_" + action.toUpperCase(),
                "support_ticket", ticketId,
                java.util.Map.of("from", String.valueOf(from), "to", String.valueOf(to)),
                null, caller.email(), caller.role(), blankToNull(req.reason()));

        log.info("Ticket {} {} by {}: {} -> {}", t.getTicketRef(), action, caller.email(), from, to);
        return toResponse(t);
    }

    @Operation(summary = "Who has held this ticket, and why it moved")
    @GetMapping("/oversight/tickets/{ticketId}/assignments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD')")
    public List<AssignmentEntry> assignmentTrail(@PathVariable UUID ticketId) {
        return assignments.findByTicketIdOrderByCreatedAtDesc(ticketId).stream()
                .map(a -> new AssignmentEntry(a.getAction(), staffName(a.getFromStaffId()),
                        staffName(a.getToStaffId()), staffName(a.getActorStaffId()),
                        a.getReason(), a.getCreatedAt()))
                .toList();
    }

    /** The states in which a ticket is still somebody's problem. */
    private static final java.util.Set<String> OPEN_STATES =
            java.util.Set.of("open", "in_progress", "waiting_on_user");

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Operation(summary = "Escalate a ticket to the next tier",
               description = "Upward, not sideways. TicketEscalationService moves the ticket to the next STAFFED tier, unassigns it, and auto-assigns a receiver if one is free. The thread id never changes, so the receiving person opens the same conversation.")
    @PostMapping("/tickets/{ticketId}/escalate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public TicketResponse escalate(@PathVariable UUID ticketId,
                                    @Valid @RequestBody EscalateRequest req,
                                    @AuthenticationPrincipal StaffPrincipal caller) {
        /*
         * ═════════════════════════════════════════════════════════════════════════════════════
         * THIS ENDPOINT DID NOT EXIST. Session 64.
         * ═════════════════════════════════════════════════════════════════════════════════════
         * TicketEscalationService was written in Session 58, complete: tier ladder, reason floor,
         * "you can only escalate what you hold", auto-assignment to the receiving tier, a full
         * ticket_escalation audit trail, and a database CHECK enforcing upward-only moves.
         *
         * Nothing ever called it. No controller, no route, no button. Six sessions of tickets
         * could not be escalated by anybody, and the only sign was that `escalation_count` was
         * always zero — which reads like "we never need to escalate", not like "escalation is
         * unreachable".
         *
         * The seventh instance of the same lesson in this codebase: A FEATURE IS SHIPPED WHEN
         * SOMETHING CALLS IT. A service class with no caller is a design document that compiles.
         */
        SupportTicket t = escalation.escalate(ticketId, req.reason(), caller);
        return toResponse(t);
    }

    @Operation(summary = "Transfer a ticket to another desk",
               description = "Sideways, not upward: finance, ops or moderation. The thread and its whole history move with it — the receiving agent opens the same conversation, and the customer never re-explains.")
    @PostMapping("/tickets/{ticketId}/transfer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @Transactional
    public TicketResponse transfer(@PathVariable UUID ticketId,
                                    @Valid @RequestBody TransferRequest req,
                                    @AuthenticationPrincipal StaffPrincipal caller) {
        SupportTicket t = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));

        String toDesk = req.toDesk().trim().toLowerCase();
        if (!handling.isValidDesk(toDesk)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown desk. Pick support, finance, ops or moderation.");
        }
        if (toDesk.equals(t.getHandlingDesk())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This ticket is already with that team.");
        }
        /*
         * The same ten-character bar as escalation, and for the same reason. A transfer with no
         * explanation forces the receiving desk to read the entire thread and guess what was
         * already tried — which is exactly the cost the transfer was supposed to avoid.
         */
        if (req.reason() == null || req.reason().trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say what you need from them and what you've already done — they read this "
                    + "instead of the whole thread.");
        }

        UUID previousHolder = t.getAssignedStaffId();
        // Captured BEFORE the setter. Reading it afterwards logs the new desk as the old one — an
        // audit entry saying "finance -> finance" is worse than none, because it looks correct.
        String fromDesk = t.getHandlingDesk();

        t.setHandlingDesk(toDesk);
        t.setDeskTransferReason(req.reason().trim());
        t.setDeskChangedAt(Instant.now());
        /*
         * Unassign on transfer.
         *
         * The previous holder is on a different desk and is not going to work it; leaving it
         * assigned to them keeps it out of the receiving desk's unassigned list — where somebody
         * would actually pick it up — while inflating a count for work nobody is doing. The
         * receiving desk claims it, which is also the moment we learn they have seen it.
         */
        t.setAssignedStaffId(null);
        t.touch();
        tickets.save(t);

        assignments.save(new com.bmp.admin.entities.TicketAssignment(
                ticketId, previousHolder, null, caller.staffId(),
                com.bmp.admin.entities.TicketAssignment.TRANSFER, req.reason().trim()));

        /*
         * A visible message in the thread, not an internal note.
         *
         * The customer is entitled to know their question changed hands — it explains a pause they
         * would otherwise read as being ignored, and it tells them not to repeat themselves. The
         * REASON stays internal (it can contain account details and candid assessments); only the
         * fact of the move is shown.
         */
        messages.save(new com.bmp.admin.entities.SupportMessage(
                ticketId, "bmp_staff", caller.staffId(),
                "Passed to our " + handling.deskLabel(toDesk).toLowerCase()
                        + " team, along with everything you've already told us. "
                        + "You don't need to explain it again.",
                null, false));

        audit.record("bmp_staff", caller.staffId(), "TICKET_TRANSFERRED", "support_ticket", ticketId,
                java.util.Map.of("toDesk", toDesk, "fromDesk", String.valueOf(fromDesk)),
                null, caller.email(), caller.role(), req.reason().trim());

        /*
         * TELL THE REQUESTER. Session 64.
         *
         * The thread now carries a visible message, but a message inside the app is only seen by
         * somebody who opens the app. The whole reason a handover is worth announcing is that it
         * explains a PAUSE — and the person experiencing that pause is, by definition, not
         * watching the screen.
         *
         * Reuses SupportTicketReplied rather than inventing a transfer event: from the requester's
         * side this IS a reply — something was said in their thread — and a second event type would
         * mean a second handler, a second template, and two places for the notification to be
         * forgotten.
         *
         * Best-effort by the outbox's design: the transfer is already committed, and a notification
         * failing must never undo a handover that has happened.
         */
        if (t.getRequesterEmail() == null || t.getRequesterEmail().isBlank()) {
            // Loud, matching the reply path: the handover happened and the person was not told.
            log.warn("Ticket {} was transferred to {} but has NO requester email — they have NOT "
                    + "been told about the pause. Contact them another way.", t.getTicketRef(), toDesk);
        } else {
            outbox.publish(new com.bmp.common.events.SupportTicketReplied(
                    t.getId(), t.getTicketRef(), t.getSubject(),
                    "Your question has been passed to our " + handling.deskLabel(toDesk).toLowerCase()
                            + " team, along with everything you've already told us. "
                            + "You don't need to explain it again.",
                    staffName(caller.staffId()),
                    t.getRequesterEmail(),
                    t.getRequesterName()));
        }

        log.info("Ticket {} transferred {} -> {} by {}", t.getTicketRef(), fromDesk, toDesk, caller.email());
        return toResponse(t);
    }

    private TicketResponse toResponse(SupportTicket t) {
        // Derived once. Calling forTicket twice in one response is cheap but invites the two calls
        // to drift apart if either ever becomes non-deterministic.
        var state = handling.forTicket(t);
        return new TicketResponse(
                t.getId(), t.getTicketRef(), t.getSubject(), t.getCategory(), t.getStatus(),
                t.getPriority(),
                // Session 56 — resolved from bmp-user for customer-raised tickets; null for
                // salon/staff ids, which are not bmp-user ids. See requesterName.
                // Session 64 — the V013 snapshot first, falling back to the live lookup for
                // tickets raised before that column existed. Preferring the snapshot also means the
                // queue stops making one cross-service call per row.
                t.getRequesterName() != null ? t.getRequesterName() : requesterName(t),
                // Session 45: these were passed RAW into fields named *Masked. The name promised
                // masking and nothing did it, so every staff role that can list the queue —
                // including READ_ONLY — saw full email addresses and phone numbers in the table.
                //
                // ConsoleDtos says it plainly: "Masking is only meaningful if the unmasked value
                // never leaves the server." Now it doesn't. The full value is available through
                // the audited reveal on the Users screen, which is the whole point of that flow.
                maskEmail(t.getRequesterEmail()), maskPhone(t.getRequesterPhone()),
                t.getRaisedById(),
                // Session 64 — the fields that make triage possible. See TicketResponse.
                t.getRaisedByType(),
                t.getSalonName(),
                t.isPriorityAuto(),
                priorityPolicy.explain(t.getRaisedByType(), t.getCategory(), t.isPriorityAuto()),
                t.getAssignedStaffId(),
                staffName(t.getAssignedStaffId()), t.getTier(), t.getEscalationCount(),
                t.getBookingId(), t.getSalonId(),
                t.getHandlingDesk(), handling.deskLabel(t.getHandlingDesk()), t.getDeskTransferReason(),
                state.state().name(), state.label(),
                t.getFirstResponseDueAt(), t.getFirstRespondedAt(), t.getCreatedAt(), t.getUpdatedAt());
    }

    /**
     * {@code priya@example.com} → {@code p***@example.com}.
     *
     * <p>Keeps the domain and the first character: enough for an agent to recognise "yes, that's
     * the Gmail address they mentioned" without the value itself being readable off a shared
     * screen or sitting in anyone's devtools.
     */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";                       // not an address shape; reveal nothing
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** {@code +919876543210} → {@code +91******3210}. Last four is what people verify against. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() <= 4) return "***";
        return digits.substring(0, Math.max(0, digits.length() - 4)).replaceAll("[0-9]", "*")
                + digits.substring(digits.length() - 4);
    }
}
