package com.bmp.admin.services;

import com.bmp.admin.dto.AdminDtos.*;
import com.bmp.admin.entities.SupportMessage;
import com.bmp.admin.entities.SupportTicket;
import com.bmp.admin.repositories.SupportMessageRepository;
import com.bmp.admin.repositories.SupportTicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** BMP-29: support_ticket + support_message CRUD. */
@Service
public class SupportTicketService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SupportTicketService.class);

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final PlatformSettingService settings;
    /** Session 64 — decides priority from who is asking and what about. See TicketPriorityPolicy. */
    private final TicketPriorityPolicy priorityPolicy;
    /** Session 64 — derives the one line the requester reads about who has their ticket. */
    private final TicketHandlingState handlingState;

    public SupportTicketService(SupportTicketRepository tickets, SupportMessageRepository messages,
                                PlatformSettingService settings, TicketPriorityPolicy priorityPolicy,
                                TicketHandlingState handlingState) {
        this.tickets = tickets;
        this.messages = messages;
        this.settings = settings;
        this.priorityPolicy = priorityPolicy;
        this.handlingState = handlingState;
    }

    @Transactional
    public TicketResponse create(CreateTicketRequest req) {
        // Session 45: extracted to nextTicketRef() so the user-facing raise() path produces the
        // same format. See that method for the concurrency caveat, which is unchanged.
        SupportTicket t = new SupportTicket(nextTicketRef(), req.raisedByType(), req.raisedById(), req.bookingId(),
                req.category(), req.subject(), "open", "medium", null, null);

        // Session 23: start the SLA clock.
        //
        // Without this, first_response_due_at is null on every ticket, the breach query matches
        // nothing, and the console's "overdue" counter reads zero forever — while customers wait.
        // A metric that is structurally incapable of being non-zero is worse than no metric,
        // because people trust it.
        //
        // The window is configurable (platform_setting.support_first_response_hours) so it can
        // be tightened as the team grows without a deploy.
        long hours = settings.number(PlatformSettingService.SUPPORT_FIRST_RESPONSE_HOURS, 4);
        t.setFirstResponseDueAt(Instant.now().plus(hours, ChronoUnit.HOURS));

        t = tickets.save(t);
        return toResponse(t, List.of());
    }

    public TicketResponse getById(UUID id, boolean includeMessages) {
        SupportTicket t = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));
        List<MessageResponse> msgs = includeMessages
                ? messages.findByTicketIdOrderByCreatedAtAsc(id).stream().map(this::toMessageResponse).toList()
                : List.of();
        return toResponse(t, msgs);
    }

    public List<TicketResponse> list(String status, UUID assignedStaffId) {
        List<SupportTicket> found;
        if (status != null && assignedStaffId != null) {
            found = tickets.findByStatusAndAssignedStaffId(status, assignedStaffId);
        } else if (status != null) {
            found = tickets.findByStatus(status);
        } else if (assignedStaffId != null) {
            found = tickets.findByAssignedStaffId(assignedStaffId);
        } else {
            found = tickets.findAll();
        }
        return found.stream().map(t -> toResponse(t, List.of())).toList();
    }

    @Transactional
    public TicketResponse update(UUID id, UpdateTicketRequest req) {
        SupportTicket t = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));
        if (req.status() != null) {
            t.setStatus(req.status());
            if ("resolved".equals(req.status())) {
                t.setResolvedAt(Instant.now());
            }
        }
        if (req.priority() != null) {
            /*
             * A PERSON set this. Session 64.
             *
             * Flipping priorityAuto to false is what stops TicketPriorityPolicy from silently
             * restoring its own answer on the next re-derivation. An agent who downgrades a ticket
             * has made a judgement with context the policy does not have; overruling that would
             * teach agents the field does not work and to stop using it.
             */
            t.setPriority(req.priority());
            t.setPriorityAuto(false);
        }
        if (req.assignedStaffId() != null) t.setAssignedStaffId(req.assignedStaffId());
        t.touch();
        return toResponse(t, List.of());
    }

    @Transactional
    public MessageResponse addMessage(UUID ticketId, CreateMessageRequest req) {
        tickets.findById(ticketId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));
        SupportMessage m = new SupportMessage(ticketId, req.senderType(), req.senderId(), req.message(), req.attachmentUrl());
        m = messages.save(m);
        return toMessageResponse(m);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Session 45 — the requester's own view.
    // ═══════════════════════════════════════════════════════════════════════════════════════
    //
    // Called only through the service-only controller, from bmp-user, which has already verified
    // a user JWT. bmp-user tells us WHO is asking; it does not get to tell us which tickets that
    // person may read. That check lives here, next to the data, because it is the last place it
    // can be enforced and the first place a reader will look for it.

    /**
     * Open a ticket on behalf of a signed-in user, with their first message attached.
     *
     * <p>Sets {@code salon_id} and the requester's contact details, neither of which
     * {@link #create} does — it predates both columns.
     */
    @Transactional
    public MyTicketResponse raise(RaiseTicketRequest req) {
        /*
         * Session 64 — priority is DERIVED, not hardcoded to "medium".
         *
         * Every in-app ticket used to open at medium regardless of who raised it, so a salon
         * reporting that it cannot take bookings entered the queue at exactly the same rank as a
         * customer asking how to change their name. The `priority` column existed and the console
         * even sorted by it; there was simply nothing putting a useful value in it.
         *
         * `priorityAuto` stays true here so a later re-derivation is permitted. The moment an agent
         * changes it, it flips false and the system stops touching it.
         */
        String priority = priorityPolicy.derive(req.raisedByType(), req.category());

        SupportTicket t = new SupportTicket(nextTicketRef(), req.raisedByType(), req.raisedById(),
                req.bookingId(), req.category(), req.subject(), "open", priority, null, null);

        t.setSalonId(req.salonId());
        t.setRequesterEmail(req.requesterEmail());
        t.setRequesterPhone(req.requesterPhone());
        // Snapshots, per V013. Blank-to-null so an empty string never renders as a nameless name.
        t.setRequesterName(blankToNull(req.requesterName()));
        t.setSalonName(blankToNull(req.salonName()));

        log.info("Ticket raised by {} ({}) category={} -> priority={}",
                req.raisedByType(), req.requesterName(), req.category(), priority);

        // Same SLA clock as create() — see that method for why a null due-date makes the
        // console's overdue counter structurally incapable of being non-zero.
        long hours = settings.number(PlatformSettingService.SUPPORT_FIRST_RESPONSE_HOURS, 4);
        t.setFirstResponseDueAt(Instant.now().plus(hours, ChronoUnit.HOURS));
        t = tickets.save(t);

        // The description becomes message #1 rather than a separate column. It IS the first
        // thing said in the conversation, and modelling it as anything else means the thread
        // starts with a reply to something invisible.
        SupportMessage first = new SupportMessage(t.getId(), req.raisedByType(), req.raisedById(),
                req.description(), null, false);
        messages.save(first);

        return toMyTicket(t, List.of(first));
    }

    /** Empty and whitespace-only both mean "not supplied". Storing "" renders as a blank name. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Every ticket this user raised — plus, for salon staff, everything raised for their salon. */
    public List<MyTicketResponse> listMine(UUID userId, UUID salonId) {
        List<SupportTicket> mine = tickets.findByRaisedByIdOrderByCreatedAtDesc(userId);

        if (salonId != null) {
            // Union, de-duplicated: a ticket the owner raised themselves appears in both lists,
            // and showing it twice would look like a double-submit.
            java.util.LinkedHashMap<UUID, SupportTicket> merged = new java.util.LinkedHashMap<>();
            for (SupportTicket t : mine) merged.put(t.getId(), t);
            for (SupportTicket t : tickets.findBySalonIdOrderByCreatedAtDesc(salonId)) {
                merged.putIfAbsent(t.getId(), t);
            }
            mine = new java.util.ArrayList<>(merged.values());
            mine.sort(java.util.Comparator.comparing(SupportTicket::getCreatedAt).reversed());
        }

        // No messages in the list view: a thread can be long, and nobody reads twenty threads at
        // once. The detail call fetches them.
        return mine.stream().map(t -> toMyTicket(t, List.of())).toList();
    }

    /** One ticket, with its thread — only if this user is allowed to see it. */
    public MyTicketResponse getMine(UUID ticketId, UUID userId, UUID salonId) {
        SupportTicket t = requireVisibleTo(ticketId, userId, salonId);
        return toMyTicket(t, messages.findByTicketIdOrderByCreatedAtAsc(ticketId));
    }

    /** Reply to a ticket you can see. Reopens it if BMP had marked it waiting on you. */
    @Transactional
    public MyTicketResponse replyAsUser(UUID ticketId, UUID userId, UUID salonId,
                                        String senderType, String body) {
        SupportTicket t = requireVisibleTo(ticketId, userId, salonId);

        if ("closed".equals(t.getStatus())) {
            // Deliberately a 409 rather than silently reopening. A closed ticket is a finished
            // conversation, and a reply to it is almost always a NEW problem that deserves its
            // own SLA clock and its own place in the queue — not a resurrection that skips
            // triage and lands at the bottom of an old thread.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "TICKET_CLOSED: this ticket is closed. Please raise a new one and mention "
                    + t.getTicketRef() + ".");
        }

        messages.save(new SupportMessage(ticketId, senderType, userId, body, null, false));

        // The user has answered, so the ball is ours again. Without this a ticket parked in
        // waiting_on_user stays parked after the user replies, and drops out of the queue the
        // agents actually watch — the reply is received and never read.
        if ("waiting_on_user".equals(t.getStatus()) || "resolved".equals(t.getStatus())) {
            t.setStatus("open");
            t.setResolvedAt(null);
        }
        t.touch();

        return toMyTicket(t, messages.findByTicketIdOrderByCreatedAtAsc(ticketId));
    }

    /**
     * Load a ticket only if this user may see it. 404 (not 403) when they may not.
     *
     * <p>404 on purpose: a 403 confirms the ticket exists, which turns sequential ids into a
     * census of how much support traffic the platform handles. Same reasoning as
     * {@code requirePhotoOfSalon} in bmp-salon.
     */
    private SupportTicket requireVisibleTo(UUID ticketId, UUID userId, UUID salonId) {
        SupportTicket t = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));

        boolean mine = userId.equals(t.getRaisedById());
        boolean sameSalon = salonId != null && salonId.equals(t.getSalonId());
        if (!mine && !sameSalon) {
            log.warn("User {} tried to read ticket {} raised by {} (salon {})",
                    userId, ticketId, t.getRaisedById(), t.getSalonId());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        }
        return t;
    }

    /**
     * Map for the REQUESTER, dropping internal notes.
     *
     * <p><b>This filter is the whole reason there are two response shapes.</b> V003 added
     * {@code internal_note} precisely so staff could talk to each other inside a ticket, and
     * {@link #toResponse} has never filtered it — harmless while the only caller was
     * service-to-service, and a disaster the moment a customer can read a thread. Handled by
     * giving the user path a method that CANNOT return notes, rather than a flag on the existing
     * one that a future call site could forget to set.
     */
    private MyTicketResponse toMyTicket(SupportTicket t, List<SupportMessage> thread) {
        List<ThreadMessage> visible = thread.stream()
                .filter(m -> !m.isInternalNote())
                .map(m -> new ThreadMessage(m.getId(), m.getSenderType(), m.getMessageText(),
                        m.getCreatedAt()))
                .toList();

        // "Awaiting us" = open or in progress and not yet resolved. waiting_on_user means we've
        // asked them something, so the ball is theirs.
        boolean awaitingUs = !"waiting_on_user".equals(t.getStatus())
                && !"resolved".equals(t.getStatus())
                && !"closed".equals(t.getStatus());

        // Session 64 — what the person waiting is told. See MyTicketResponse and TicketHandlingState.
        var handling = handlingState.forTicket(t);

        return new MyTicketResponse(t.getId(), t.getTicketRef(), t.getCategory(), t.getSubject(),
                t.getStatus(), t.getBookingId(), awaitingUs,
                handling.state().name(), handling.label(), handling.detail(), handling.deskLabel(),
                t.getCreatedAt(), t.getResolvedAt(),
                visible);
    }

    /**
     * Next ticket reference. Extracted from {@link #create} so both creation paths produce the
     * same format instead of drifting.
     *
     * <p>Carries {@link #create}'s known flaw unchanged: counting existing rows is NOT safe under
     * FIXED in Session 48 (V008). This used to count rows and add one, which two concurrent
     * tickets could do simultaneously — both taking the same number, one then dying on the unique
     * index and surfacing to a customer as a 500 while raising a support ticket. It now draws from
     * a Postgres sequence, which is atomic and cannot repeat.
     */
    private String nextTicketRef() {
        // V008 (Session 48): atomic. The old count(*)+1 raced with itself — see the sequence's
        // comment. The year is still a prefix so references stay readable; only the number comes
        // from the sequence, which is why it never needs resetting.
        return "TCK-" + Instant.now().atZone(ZoneOffset.UTC).getYear() + "-"
                + String.format("%05d", tickets.nextTicketNumber());
    }


    private TicketResponse toResponse(SupportTicket t, List<MessageResponse> msgs) {
        return new TicketResponse(t.getId(), t.getTicketRef(), t.getRaisedByType(), t.getRaisedById(),
                t.getBookingId(), t.getCategory(), t.getSubject(), t.getStatus(), t.getPriority(),
                t.getAssignedStaffId(), t.getCreatedAt(), t.getResolvedAt(), msgs);
    }

    private MessageResponse toMessageResponse(SupportMessage m) {
        return new MessageResponse(m.getId(), m.getTicketId(), m.getSenderType(), m.getSenderId(),
                m.getMessageText(), m.getAttachmentUrl(), m.getCreatedAt());
    }
}
