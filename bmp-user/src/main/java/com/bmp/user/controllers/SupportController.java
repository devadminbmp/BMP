package com.bmp.user.controllers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.user.client.SupportServiceClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Support, for everyone who is not BMP staff. Session 45.
 *
 * <h2>The gap this closes</h2>
 * bmp-admin had a complete support desk — ticket + message tables, SLA clocks, canned responses,
 * triage, assignment, and five console pages. Staff could list, read, reply and close. And
 * <b>nothing anywhere could open a ticket</b>: {@code SupportTicketController} was
 * {@code ROLE_SERVICE} and had no callers in any of the three repos. A salon owner whose payout
 * looked wrong, or a customer charged a cancellation fee they disputed, had no route to a human
 * at all. This is that route.
 *
 * <h2>Identity comes from the token. Always. Only.</h2>
 * bmp-admin's {@code CreateTicketRequest} takes {@code raisedByType} and {@code raisedById} as
 * body fields — correct for a service-to-service API, catastrophic if exposed to end users: a
 * customer could post {@code raisedByType: "bmp_staff"} and file tickets that render to agents as
 * though support wrote them, or set {@code raisedById} to another person and read the replies.
 *
 * <p>So this controller ignores those fields entirely. Type, id and salon scope are derived from
 * {@link AuthenticatedUser}, which came from a signature-verified JWT. The request record here
 * <em>has no identity fields at all</em> — not "has them and overwrites them", which is a rule
 * one refactor can quietly drop. <b>Authorise the path, then trust the body</b> is the shape that
 * keeps recurring in this codebase; the fix is a body with nothing worth trusting in it.
 *
 * <h2>Salon scope is OWNER-ONLY, and the first version of this got it wrong</h2>
 * A manager reports a broken payout on Tuesday; the owner asks about it on Friday. Without salon
 * scope the owner sees nothing, raises a duplicate, and support answers twice. So the owner gets
 * a view of every ticket raised for their salon.
 *
 * <p><b>Managers and stylists deliberately do NOT.</b> The first version passed {@code salonId}
 * for any salon-scoped role, which reads as "support is a salon-level concern" and is right up
 * until you consider what an owner actually writes to BMP about. "I need to revoke my manager's
 * access — money has gone missing" is an account_issue ticket, scoped to the salon, and under
 * that rule the manager it concerns could read it in their own Help tab. The category allowlist
 * doesn't help: it's the ordinary categories that carry this, not a special one.
 *
 * <p>So visibility follows the same principle as everything else here: the OWNER oversees the
 * business, and staff see their own correspondence. A manager loses nothing they need — their own
 * tickets, and their own replies, are all still there.
 */
@RestController
@RequestMapping("/api/v1/support")
@Tag(name = "Support", description = "Raise and follow up support tickets. Customers, salon owners, managers and stylists.")
public class SupportController {

    private static final Logger log = LoggerFactory.getLogger(SupportController.class);

    /**
     * Categories a user may choose.
     *
     * <p>An allowlist, not free text, for two reasons. It lands in a column the console filters
     * and reports on, so an open field turns routing into string-matching against whatever people
     * typed. And it is the one field that decides which agent sees the ticket first — a
     * misrouted payout complaint sits in the wrong queue for a day.
     *
     * <p>Matches the values V002 enumerated on {@code support_ticket.category}, plus
     * {@code salon_listing} for "customers can't find me", which is the single most common salon
     * question and had nowhere to go.
     */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "booking_issue", "payment_issue", "refund_dispute", "account_issue",
            "stylist_complaint", "salon_listing", "app_problem", "other");

    private final SupportServiceClient support;
    /**
     * Session 64 — to put a NAME on the ticket instead of a UUID.
     *
     * This is a LOCAL read: bmp-user owns the users table, so resolving the requester's name costs
     * one query against our own database and no cross-service call at all. That is the whole reason
     * the snapshot is taken here rather than in bmp-admin, where the same fact would require an
     * HTTP hop per ticket per page load.
     */
    private final com.bmp.user.repositories.UsersRepository usersRepo;

    public SupportController(SupportServiceClient support,
                             com.bmp.user.repositories.UsersRepository usersRepo) {
        this.support = support;
        this.usersRepo = usersRepo;
    }

    /**
     * What the user actually types.
     *
     * <p>Note what is NOT here: no {@code raisedByType}, no {@code raisedById}, no
     * {@code salonId}. See the class javadoc — a field that cannot be sent cannot be forged.
     *
     * @param bookingId optional, and the single most useful field on the form. A ticket that
     *                  arrives already attached to a booking lets an agent answer on first read
     *                  instead of spending a round trip — and the whole first-response SLA —
     *                  asking "which appointment?".
     */
    public record RaiseRequest(
            @NotBlank @Size(max = 30) String category,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 4000) String description,
            UUID bookingId) {}

    /** A reply on an existing thread. */
    public record ReplyRequest(@NotBlank @Size(max = 4000) String body) {}

    @Operation(summary = "Raise a support ticket",
            description = "Opens a ticket and posts your description as its first message. Who you "
                    + "are is taken from your login, not from the request.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/tickets")
    public ResponseEntity<SupportServiceClient.MyTicket> raise(
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody RaiseRequest req) {

        if (!ALLOWED_CATEGORIES.contains(req.category())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "UNKNOWN_CATEGORY: pick one of " + ALLOWED_CATEGORIES);
        }

        SupportServiceClient.MyTicket created = support.raise(new SupportServiceClient.RaiseTicket(
                raisedByType(me),      // from the token
                me.userId(),           // from the token
                me.salonId(),          // from the token; null for customers
                req.bookingId(),
                req.category(),
                req.subject().trim(),
                req.description().trim(),
                // Contact details are deliberately NOT taken from the request either. bmp-admin
                // has them via the user record when it needs them, and letting a caller supply a
                // reply-to address is how a support system becomes a way to redirect answers
                // about someone else's account.
                null,
                null,
                /*
                 * The requester's NAME, though — sent deliberately, and note the difference from
                 * the two nulls above. Session 64.
                 *
                 * Contact details are withheld because letting a caller influence where a reply
                 * goes is how a support system becomes a redirection attack. A display name carries
                 * no such power: it is shown to an agent so they know a salon owner from a
                 * customer, and it comes from the token here rather than from the request body, so
                 * it is not caller-supplied either.
                 *
                 * Without it the console shows a UUID, which is exactly the state that made every
                 * in-app ticket anonymous to the person answering it.
                 */
                displayName(me),
                null));

        log.info("Support ticket {} raised by {} ({}) category={}",
                created.ticketRef(), me.userId(), raisedByType(me), req.category());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "My tickets",
            description = "Yours, plus everything raised for your salon if you are an owner, "
                    + "manager or stylist.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/tickets")
    public List<SupportServiceClient.MyTicket> listMine(@AuthenticationPrincipal AuthenticatedUser me) {
        return support.listMine(me.userId(), me.salonId());
    }

    @Operation(summary = "One ticket and its conversation",
            description = "404 if it isn't yours — deliberately not 403, which would confirm the "
                    + "ticket exists.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/tickets/{ticketId}")
    public SupportServiceClient.MyTicket getOne(@AuthenticationPrincipal AuthenticatedUser me,
                                                 @PathVariable UUID ticketId) {
        return support.getMine(ticketId, me.userId(), salonScopeFor(me));
    }

    @Operation(summary = "Reply to your ticket",
            description = "Puts the ticket back in BMP's queue if it was waiting on you.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/tickets/{ticketId}/messages")
    public ResponseEntity<SupportServiceClient.MyTicket> reply(
            @AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReplyRequest req) {

        return ResponseEntity.status(HttpStatus.CREATED).body(
                support.reply(ticketId, new SupportServiceClient.UserReply(
                        me.userId(), salonScopeFor(me), raisedByType(me), req.body().trim())));
    }

    /**
     * The salon whose tickets this user may see beyond their own — OWNER only.
     *
     * <p>Returning null for everyone else is what confines a manager to their own correspondence.
     * Note this is used for READS ONLY; {@code raise()} still stamps {@code salonId} from the
     * token on every salon-staff ticket, so a manager's ticket IS filed against the salon and the
     * owner can see it. Writing the scope and reading by it are different questions, and
     * conflating them was the bug.
     *
     * <p>The check is on the role rather than on a permissions table because {@code salonId} in
     * the token already proves membership; what's in question here is seniority, and the role is
     * the only thing that carries it.
     */
    private static UUID salonScopeFor(AuthenticatedUser me) {
        return me != null && "SALON_OWNER".equals(me.role()) ? me.salonId() : null;
    }

    /**
     * JWT role → the {@code raised_by_type} vocabulary V002 defined on the column.
     *
     * <p>Two vocabularies exist because they answer different questions: the role says what this
     * person may DO, {@code raised_by_type} says who an agent is TALKING TO. They happen to line
     * up today, and mapping explicitly means a new role doesn't silently write an unrecognised
     * value into a column the console groups by.
     *
     * <p>The default is {@code customer} — the least privileged reading — so an unmapped role
     * fails safe rather than being presented to an agent as staff.
     */
    /**
     * A name for the agent to see, from the token only.
     *
     * <p>Returns null rather than a placeholder when nothing is known: bmp-admin falls back to its
     * own lookup for that case, and a literal "Unknown" stored on the ticket would defeat it
     * forever. An honest blank is recoverable; a fabricated value is not.
     */
    private String displayName(AuthenticatedUser me) {
        try {
            return usersRepo.findById(me.userId())
                    .map(u -> u.getName())
                    .filter(n -> n != null && !n.isBlank())
                    .orElse(null);
        } catch (Exception e) {
            /*
             * Never let this fail the ticket.
             *
             * The name is a convenience for the agent; the ticket is somebody asking for help. If
             * the lookup throws, bmp-admin still falls back to its own resolution, and the worst
             * case is the console showing what it showed before this session. Losing the ticket
             * over a nice-to-have would be a far worse trade.
             */
            log.warn("Could not resolve a display name for {} — raising the ticket anyway ({})",
                    me.userId(), e.toString());
            return null;
        }
    }

    private static String raisedByType(AuthenticatedUser me) {
        if (me == null || me.role() == null) {
            // isAuthenticated() should make this unreachable; if the principal is ever missing,
            // that is a bug in the filter chain and not something to paper over with a guess.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN");
        }
        return switch (me.role()) {
            case "SALON_OWNER" -> "salon_owner";
            case "MANAGER" -> "manager";
            case "STYLIST" -> "stylist";
            default -> "customer";
        };
    }
}
