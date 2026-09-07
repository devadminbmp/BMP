package com.bmp.admin.services;

import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.entities.SupportTicket;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.SupportTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Who gets this ticket. Session 57.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS REPLACES
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Assignment was a checkbox: {@code UpdateTicketRequest.assignToMe}. Whoever opened the queue and
 * clicked first took the ticket. That has two failure modes, and both appear the moment there is
 * more than one agent:
 *
 * <ul>
 *   <li><b>Cherry-picking.</b> Easy tickets get claimed, hard ones sit. The queue's oldest item
 *       is the one nobody wants, which is exactly the one a customer is waiting on.</li>
 *   <li><b>Nothing is anybody's.</b> An unclaimed ticket has no owner, so no one is accountable
 *       for the first reply — the metric every support desk actually lives or dies by.</li>
 * </ul>
 *
 * Darshan: <i>"each support member must get different tickets… whenever we add admin or support
 * team members, new signups should be included in the queue."</i> Both fall out of assigning on
 * arrival rather than on claim.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * LEAST-LOADED, NOT STRICT ROUND-ROBIN
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * A pure rotation deals the Nth ticket to the Nth agent regardless of what they are already
 * holding, so an agent who happens to draw four hard tickets keeps receiving more while a
 * colleague who closed theirs sits idle. Ordering by {@code open_ticket_count} self-corrects:
 * whoever has capacity gets the next one. It is what every real desk does, and it is one index
 * ({@code idx_staff_assignable}).
 *
 * <p>A NEW JOINER HAS A COUNT OF ZERO, so they are first in line automatically. That is the whole
 * mechanism behind "new members are included in the queue" — no roster to maintain, no list to
 * remember to update, and nothing to forget when somebody joins.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * TIER IS A FLOOR AND A CEILING
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * A ticket is assigned to somebody at EXACTLY its tier. Not "this tier or above" — an ops admin
 * should not be handed routine first-line work simply because they were idle, and a ticket
 * escalated to ops must never fall back onto an L1 agent who cannot action it.
 *
 * <p>Tier 0 (finance, read-only analysts) is excluded by {@link BmpStaff#isAssignable()}. They can
 * read the queue; they can never hold an item in it.
 */
@Service
public class TicketAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(TicketAssignmentService.class);

    private final BmpStaffRepository staff;
    private final SupportTicketRepository tickets;
    /** Session 59 — auto / manual / analyst, per tier. See QueueConfig. */
    private final com.bmp.admin.repositories.QueueConfigRepository queueConfig;

    public TicketAssignmentService(BmpStaffRepository staff, SupportTicketRepository tickets,
                                    com.bmp.admin.repositories.QueueConfigRepository queueConfig) {
        this.staff = staff;
        this.tickets = tickets;
        this.queueConfig = queueConfig;
    }

    /**
     * Give this ticket an owner at its current tier, or leave it unassigned.
     *
     * <h2>Unassigned is a legitimate outcome, not a failure</h2>
     * At 2am there may be nobody at tier 2 accepting work. The ticket stays in the pool with a
     * null assignee and is picked up when somebody comes on shift — which is honest. The
     * alternative, assigning to a person who is asleep, makes the queue LOOK handled while the
     * customer waits exactly as long, and hides the staffing gap that caused it.
     *
     * <p>Never throws. A ticket that exists but is unassigned is recoverable; a raise that failed
     * because assignment failed is a customer who could not ask for help.
     *
     * @return the assignee, or empty when nobody at that tier is available
     */
    @Transactional
    public Optional<BmpStaff> autoAssign(SupportTicket ticket) {
        try {
            /*
             * Session 59 — the mode decides WHO chooses, before we choose anybody.
             *
             * Darshan asked for three: automatic, manual, or a business analyst distributing. All
             * three run through this one method rather than three code paths, because the parts
             * that are easy to get wrong — keeping open_ticket_count honest, never assigning to
             * somebody on leave, never crossing tiers — must not be reimplemented per mode.
             */
            var config = queueConfig.findByTier(ticket.getTier()).orElse(null);

            if (config != null && config.isManual()) {
                // Deliberately unassigned. The pool is the queue, and somebody claims from it.
                log.debug("Tier {} is on manual assignment — ticket {} left in the pool.",
                        ticket.getTier(), ticket.getTicketRef());
                return Optional.empty();
            }

            if (config != null && config.isAnalyst()) {
                /*
                 * Everything lands on one person, who distributes it by reassigning.
                 *
                 * Checked for assignability like anyone else: an analyst on leave must not become
                 * a black hole that silently swallows the whole tier's incoming work.
                 */
                var analyst = staff.findById(config.getAnalystStaffId()).orElse(null);
                if (analyst == null || !analyst.isAssignable()) {
                    log.warn("Tier {} is set to analyst mode but {} is unavailable — ticket {} "
                            + "stays in the pool rather than being assigned to nobody.",
                            ticket.getTier(), config.getAnalystStaffId(), ticket.getTicketRef());
                    return Optional.empty();
                }
                ticket.setAssignedStaffId(analyst.getId());
                analyst.adjustOpenTickets(+1);
                staff.save(analyst);
                log.info("Ticket {} routed to analyst {} for distribution.",
                        ticket.getTicketRef(), analyst.getEmail());
                return Optional.of(analyst);
            }

            Optional<BmpStaff> candidate = staff.findNextAssignee(ticket.getTier());
            if (candidate.isEmpty()) {
                log.warn("Ticket {} could not be auto-assigned — nobody at tier {} is accepting "
                        + "work. It stays in the unassigned pool. If this is not the middle of the "
                        + "night, it is a staffing gap and not a bug.",
                        ticket.getTicketRef(), ticket.getTier());
                return Optional.empty();
            }

            BmpStaff assignee = candidate.get();

            /*
             * The load cap. Somebody already at the ceiling is not "least loaded", they are full —
             * and handing them one more makes the queue look staffed while nothing moves. Leaving
             * it unassigned puts the overflow where a lead can see it.
             */
            int cap = config == null ? 0 : config.getMaxOpenPerAgent();
            if (cap > 0 && assignee.getOpenTicketCount() >= cap) {
                log.warn("Everyone at tier {} is at the {}-ticket cap — {} stays in the pool. This "
                        + "is a staffing signal, not a bug.", ticket.getTier(), cap,
                        ticket.getTicketRef());
                return Optional.empty();
            }

            ticket.setAssignedStaffId(assignee.getId());
            assignee.adjustOpenTickets(+1);
            staff.save(assignee);

            log.info("Ticket {} assigned to {} at tier {} (now holding {}).",
                    ticket.getTicketRef(), assignee.getEmail(), ticket.getTier(),
                    assignee.getOpenTicketCount());
            return Optional.of(assignee);
        } catch (Exception e) {
            // See the javadoc: assignment failing must not cost the customer their ticket.
            log.error("Auto-assignment failed for ticket {} ({}). It remains unassigned and "
                    + "workable from the pool.", ticket.getTicketRef(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Hand a ticket from its current owner to a new one, keeping both counts honest.
     *
     * <p>Used by escalation and by a lead reassigning within their team. The decrement on the old
     * owner is the half that gets forgotten, and forgetting it is how an agent's count drifts
     * upward until they stop receiving work for no visible reason.
     */
    @Transactional
    public void reassign(SupportTicket ticket, UUID newAssigneeId) {
        UUID previous = ticket.getAssignedStaffId();
        if (previous != null && previous.equals(newAssigneeId)) return;

        if (previous != null) {
            staff.findById(previous).ifPresent(p -> {
                p.adjustOpenTickets(-1);
                staff.save(p);
            });
        }
        ticket.setAssignedStaffId(newAssigneeId);
        if (newAssigneeId != null) {
            staff.findById(newAssigneeId).ifPresent(n -> {
                n.adjustOpenTickets(+1);
                staff.save(n);
            });
        }
    }

    /**
     * The ticket is finished — give its owner their capacity back.
     *
     * <p>Idempotent at the count level ({@code adjustOpenTickets} floors at zero), because
     * resolving an already-resolved ticket is something a double-click does.
     */
    @Transactional
    public void released(SupportTicket ticket) {
        if (ticket.getAssignedStaffId() == null) return;
        staff.findById(ticket.getAssignedStaffId()).ifPresent(s -> {
            s.adjustOpenTickets(-1);
            staff.save(s);
        });
    }

    /**
     * Recompute one person's open count from the tickets themselves.
     *
     * <h2>Why a denormalised counter needs a repair path</h2>
     * {@code open_ticket_count} is maintained by increments, and increments drift — a crash between
     * saving the ticket and saving the staff row, a ticket closed by a path that forgot to call
     * {@link #released}. Drift here is quiet: the agent simply stops being chosen, and nobody can
     * tell from the queue why.
     *
     * <p>So the truth is always recoverable by counting, and this is what a support lead runs when
     * somebody says "I'm not getting any tickets".
     */
    @Transactional
    public int recount(UUID staffId) {
        BmpStaff s = staff.findById(staffId).orElse(null);
        if (s == null) return 0;
        int real = (int) tickets.countOpenForAssignee(staffId);
        if (real != s.getOpenTicketCount()) {
            log.warn("Open-ticket count for {} was {} and is actually {} — corrected.",
                    s.getEmail(), s.getOpenTicketCount(), real);
        }
        s.adjustOpenTickets(real - s.getOpenTicketCount());
        staff.save(s);
        return real;
    }
}
