package com.bmp.admin.services;

import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.entities.SupportTicket;
import com.bmp.admin.entities.TicketEscalation;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.SupportTicketRepository;
import com.bmp.admin.repositories.TicketEscalationRepository;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Passing a ticket up the ladder. Session 57.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE LADDER
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <pre>
 *   1  support_agent   front line — owns the first reply, resolves the common cases
 *   2  support_lead    takes what L1 cannot; owns the queue's health          [ADDED Session 57]
 *   3  ops_admin       policy, salon standing, anything with consequences
 *   4  super_admin     the owner of BMP; everything, including who is staff
 * </pre>
 *
 * <p>BMP had 1, 3 and 4 and no 2. That gap is why the requirement reads as "support escalates to
 * ops admin" — with nothing in between, ops becomes the first and only escalation, and a role meant
 * for policy spends its day on individual complaints. Every comparable desk (Swiggy, Zomato,
 * Rapido, Blinkit all run Zendesk/Freshdesk-shaped operations) has this rung.
 *
 * <p><b>Off the ladder entirely:</b> {@code finance_admin} and {@code read_only} analysts, at
 * tier 0. Refunds are a different axis, not a higher rung — routing money up the support chain is
 * how a refund waits behind a haircut complaint. Analysts read; they never own.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * ONE RUNG AT A TIME
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * An agent escalates to a lead, not to the owner of the company. Allowing a jump means every
 * difficult ticket lands on whoever is most senior, the ladder stops meaning anything, and the
 * tier that exists to absorb this work never sees it.
 *
 * <p>The one exception is a tier with nobody in it: if there are no leads at all, an agent's
 * escalation goes to ops rather than nowhere. A missing rung must not trap a customer.
 */
@Service
public class TicketEscalationService {

    private static final Logger log = LoggerFactory.getLogger(TicketEscalationService.class);

    /** The top of the ladder. Nothing escalates past the owner of the platform. */
    private static final short MAX_TIER = 4;

    private final SupportTicketRepository tickets;
    private final TicketEscalationRepository escalations;
    private final BmpStaffRepository staff;
    private final TicketAssignmentService assignment;
    private final AuditLogService audit;

    public TicketEscalationService(SupportTicketRepository tickets,
                                    TicketEscalationRepository escalations,
                                    BmpStaffRepository staff,
                                    TicketAssignmentService assignment,
                                    AuditLogService audit) {
        this.tickets = tickets;
        this.escalations = escalations;
        this.staff = staff;
        this.assignment = assignment;
        this.audit = audit;
    }

    /**
     * Hand this ticket to the next tier up.
     *
     * <h2>The conversation goes with it, because it never left</h2>
     * The ticket is not recreated, copied or forked — its {@code tier} is raised and its assignee
     * changes. Every message, photo and previous escalation is still attached to the same ticket
     * id, so the receiving person opens a full thread and continues. That is the entire mechanism
     * behind "he must get the same and previous chat history".
     *
     * @param reason required, and at least ten characters (enforced again by the database).
     *               An escalation with no reason makes the next person re-read everything and
     *               guess what was already tried.
     */
    @Transactional
    public SupportTicket escalate(UUID ticketId, String reason, StaffPrincipal caller) {
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND"));

        BmpStaff me = staff.findById(caller.staffId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "UNKNOWN_STAFF"));

        if (reason == null || reason.trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say what you've already tried and what you need — the next person reads this "
                    + "instead of the whole thread.");
        }

        /*
         * You can only escalate a ticket you are actually holding — or that nobody is.
         *
         * Without this, any agent could push any ticket in the queue upward, which turns
         * escalation into a way to make somebody else's work disappear. A lead reassigning within
         * their team is a different operation and goes through TicketAssignmentService.
         */
        if (ticket.getAssignedStaffId() != null && !ticket.getAssignedStaffId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "NOT_YOUR_TICKET: you can only escalate a ticket assigned to you.");
        }
        if (me.getTier() <= 0) {
            // Analysts and finance are not on the ladder — see the class note.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role can read tickets but not hold or escalate them.");
        }
        if (ticket.getTier() >= MAX_TIER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is already with the platform owner — there is nowhere above it. "
                    + "Resolve it or reassign it.");
        }

        short from = ticket.getTier();
        short to = nextStaffedTierAbove(from);

        ticket.escalateTo(to);
        // Unassign first: the count on the person handing it up must come back down even if
        // nobody at the new tier is free to take it.
        assignment.reassign(ticket, null);
        Optional<BmpStaff> receiver = assignment.autoAssign(ticket);

        escalations.save(new TicketEscalation(
                ticket.getId(), from, to, me.getId(),
                receiver.map(BmpStaff::getId).orElse(null), reason.trim()));

        tickets.save(ticket);

        audit.record("bmp_staff", me.getId(), "TICKET_ESCALATED", "support_ticket", ticket.getId(),
                java.util.Map.of("fromTier", String.valueOf(from), "toTier", String.valueOf(to),
                        "ticketRef", ticket.getTicketRef()),
                null, caller.email(), caller.role(), reason.trim());

        log.info("Ticket {} escalated {}→{} by {}; now with {}.",
                ticket.getTicketRef(), from, to, me.getEmail(),
                receiver.map(BmpStaff::getEmail).orElse("the unassigned pool"));
        return ticket;
    }

    /**
     * The next tier up that actually has somebody on it.
     *
     * <h2>Why not simply {@code from + 1}</h2>
     * A tier with nobody in it would swallow the ticket: escalated, unassigned, and invisible to
     * everyone below and above. That is worse than not escalating at all, because the agent
     * believes they have handed it on.
     *
     * <p>So an empty rung is skipped. A small platform with no leads yet escalates agent → ops
     * and keeps working, and starts using the lead tier the day somebody is hired into it — with
     * no code change and no configuration.
     */
    private short nextStaffedTierAbove(short from) {
        for (short t = (short) (from + 1); t <= MAX_TIER; t++) {
            if (!staff.findByTierAndStatusOrderByOpenTicketCountAsc(t, "active").isEmpty()) {
                return t;
            }
        }
        // Nobody anywhere above. Go up exactly one rung and leave it in that pool — the ticket is
        // still visibly escalated, and it surfaces the staffing gap rather than hiding it.
        log.warn("No staffed tier above {} — escalating one rung into an empty pool. This is a "
                + "hiring problem, and the ticket is deliberately left visible.", from);
        return (short) Math.min(from + 1, MAX_TIER);
    }

    /** The full handover trail, oldest first. Rendered above the message thread. */
    public List<TicketEscalation> trailFor(UUID ticketId) {
        return escalations.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }
}
