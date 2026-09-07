package com.bmp.admin.services;

import com.bmp.admin.entities.ApprovalDecision;
import com.bmp.admin.entities.ApprovalRequest;
import com.bmp.admin.repositories.ApprovalDecisionRepository;
import com.bmp.admin.repositories.ApprovalRequestRepository;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Raising, routing and clearing approvals — for every gated action. Session 58.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE FLOW, USING DARSHAN'S OWN EXAMPLE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <pre>
 *   Support agent wants to give ₹800 of goodwill on ticket TCK-2026-00042
 *     └─ AuthorityService.check("coupon.issue", "support_agent", 80000)
 *          → NEEDS_APPROVAL, approver = support_lead   (agent's ceiling is ₹500)
 *     └─ raise(...)  → APR-000042, pending, addressed to support_lead
 *
 *   Support lead opens their queue, sees it with the full ticket behind it
 *     ├─ approve()   → executor issues the coupon → EXECUTED
 *     ├─ reject()    → REJECTED, agent told why
 *     └─ escalate()  → moves to ops_admin, still pending, decision recorded
 * </pre>
 *
 * A refund walks the same code and a different path: support's ceiling is ZERO, so even ₹1 goes to
 * finance — because money leaving the business is not a seniority question, and the person
 * comforting an upset customer is the worst-placed person to decide it.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS CLASS DOES NOT KNOW
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Anything about coupons, refunds or suspensions. It routes, records and delegates. Every action
 * is an {@link ApprovalActionExecutor} discovered by Spring, so adding one never edits this file —
 * which is what stops a mistake in the coupon path from breaking refunds.
 */
@Service
public class ApprovalRequestService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRequestService.class);

    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;
    private final AuthorityService authority;
    private final AuditLogService audit;
    /** Session 59 — "never give back more than the customer paid". See GoodwillCapService. */
    private final GoodwillCapService goodwillCap;

    /** actionType → executor. Built once from every bean implementing the interface. */
    private final Map<String, ApprovalActionExecutor> executors;

    public ApprovalRequestService(ApprovalRequestRepository requests,
                                   ApprovalDecisionRepository decisions,
                                   AuthorityService authority,
                                   AuditLogService audit,
                                   GoodwillCapService goodwillCap,
                                   List<ApprovalActionExecutor> discovered) {
        this.requests = requests;
        this.decisions = decisions;
        this.authority = authority;
        this.audit = audit;
        this.goodwillCap = goodwillCap;
        this.executors = discovered.stream()
                .collect(Collectors.toMap(ApprovalActionExecutor::actionType, Function.identity()));
        log.info("Approval executors registered for: {}", this.executors.keySet());
    }

    /**
     * Ask for permission to do something above your own ceiling.
     *
     * <h2>The authority check runs again HERE</h2>
     * The caller has usually already asked {@code AuthorityService.check} to decide which button to
     * show. This re-checks anyway, because the first call informed a UI and this one authorises an
     * action — and a client that has been told "you need approval" can still post the raw action
     * to the underlying endpoint. Deciding twice is cheap; trusting the first decision is how a
     * client-side check becomes the only check.
     *
     * @param valuePaise 0 for actions with no amount. The matrix still routes correctly, because a
     *                   0 ceiling means "never, at any amount" rather than "allowed at zero".
     */
    @Transactional
    public ApprovalRequest raise(String actionType, String payloadJson, long valuePaise,
                                  UUID ticketId, String justification,
                                  StaffPrincipal caller, short callerTier) {

        if (justification == null || justification.trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why. Whoever approves this reads your reason instead of reconstructing "
                    + "the case from the ticket.");
        }

        /*
         * Session 59 — the cap that applies to EVERYONE, including the owner.
         *
         * Checked before the authority matrix on purpose: "that's more than the customer paid" is a
         * more useful refusal than "that needs a lead's approval", and telling somebody to go and
         * get approval for an amount that will be refused at execution wastes their time and an
         * approver's attention.
         *
         * bookingIdFor reads it out of the payload — the cap is a property of the action, and the
         * approval machinery deliberately knows nothing else about what is inside.
         */
        if (GoodwillCapService.applies(actionType)) {
            goodwillCap.assertWithinBookingValue(actionType, bookingIdFrom(payloadJson), valuePaise);
        }

        AuthorityService.Decision decision = authority.check(actionType, caller.role(), valuePaise);

        if (decision.verdict() == AuthorityService.Verdict.ALLOWED) {
            /*
             * They didn't need to ask. Refused rather than quietly filed, because a queue full of
             * requests that the requester could have actioned themselves is how approvers stop
             * reading the queue — and then miss the one that mattered.
             */
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You can do this yourself — no approval needed. " + decision.reason());
        }
        if (decision.verdict() == AuthorityService.Verdict.FORBIDDEN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
        if (decision.requiresTicket() && ticketId == null) {
            // Goodwill with no complaint behind it is what turns a support desk into a leak.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Raise this from the ticket it relates to — approvals of this kind must be "
                    + "attached to a customer's case.");
        }

        ApprovalRequest request = requests.save(new ApprovalRequest(
                nextRef(), actionType, payloadJson, valuePaise,
                caller.staffId(), caller.role(), callerTier,
                ticketId, justification.trim(), decision.approverRole(), (short) 1));

        audit.record("bmp_staff", caller.staffId(), "APPROVAL_RAISED", "approval_request",
                request.getId(),
                Map.of("actionType", actionType, "valuePaise", String.valueOf(valuePaise),
                        "approverRole", decision.approverRole(), "ref", request.getRequestRef()),
                null, caller.email(), caller.role(), justification.trim());

        log.info("{} raised by {} ({}) for {} at {}p — waiting on {}.",
                request.getRequestRef(), caller.email(), caller.role(), actionType, valuePaise,
                decision.approverRole());
        return request;
    }

    /**
     * Approve, and carry the action out.
     *
     * <h2>Approval and execution are recorded separately</h2>
     * The request is marked APPROVED, the executor runs, and only then does it become EXECUTED. If
     * the executor throws, the request is FAILED with the error and <b>the approval is kept</b> —
     * somebody did approve it, and discarding that would lose a real decision and make the retry
     * look like a fresh request nobody had signed off.
     */
    @Transactional
    public ApprovalRequest approve(UUID requestId, String note, StaffPrincipal caller) {
        ApprovalRequest request = mustBePendingAndMine(requestId, caller);

        request.decide(ApprovalRequest.APPROVED, caller.staffId(), note);
        decisions.save(new ApprovalDecision(request.getId(), "approved",
                caller.staffId(), caller.role(), note));

        ApprovalActionExecutor executor = executors.get(request.getActionType());
        if (executor == null) {
            /*
             * Approved, and nothing can carry it out. This is a wiring mistake — an action_type in
             * the matrix with no executor bean — and it must be loud: the approver believes they
             * have just given a customer something.
             */
            String problem = "No executor is registered for " + request.getActionType()
                    + ". The approval stands; nothing has been carried out.";
            log.error("{} — {}", request.getRequestRef(), problem);
            request.markFailed(problem);
            requests.save(request);
            return request;
        }

        try {
            /*
             * RE-CHECKED here, days after the raise.
             *
             * A request raised on Monday can be approved on Thursday, and in between the booking
             * may have been partly refunded, cancelled with a fee, or moved to a cheaper service.
             * An amount inside the cap on Monday can be outside it by Thursday, and this is the
             * last moment before money leaves.
             */
            if (GoodwillCapService.applies(request.getActionType())) {
                goodwillCap.assertWithinBookingValue(request.getActionType(),
                        bookingIdFrom(request.getPayload()), request.getValuePaise());
            }
            String outcome = executor.execute(request);
            request.markExecuted();
            requests.save(request);

            /*
             * Session 59 (V012) — record it against the booking so the next check can see it.
             *
             * Keyed by the approval id, which is unique in goodwill_grant: a request that failed
             * and was retried keeps its approval, so without that uniqueness one ₹400 coupon would
             * consume ₹800 of the customer's ceiling and the person chasing it would be refused for
             * a reason nobody could find.
             */
            goodwillCap.record(request.getActionType(),
                    bookingIdFrom(request.getPayload()), request.getValuePaise(),
                    request.getId(), caller.staffId(), caller.role(), outcome);

            audit.record("bmp_staff", caller.staffId(), "APPROVAL_APPROVED", "approval_request",
                    request.getId(),
                    Map.of("actionType", request.getActionType(), "ref", request.getRequestRef(),
                            "outcome", outcome == null ? "" : outcome),
                    null, caller.email(), caller.role(), note);

            log.info("{} approved by {} and executed: {}",
                    request.getRequestRef(), caller.email(), outcome);
        } catch (Exception e) {
            request.markFailed(e.toString());
            requests.save(request);
            log.error("{} was APPROVED by {} but execution failed ({}). The approval is kept so it "
                    + "can be retried without being re-approved.",
                    request.getRequestRef(), caller.email(), e.toString());
        }
        return request;
    }

    /**
     * Refuse it. Ends the request — the requester is told, and may raise a new one with more.
     *
     * <p>A reason is required. "Rejected" with no explanation sends the agent back to the customer
     * with nothing to say, and they will simply raise it again.
     */
    @Transactional
    public ApprovalRequest reject(UUID requestId, String note, StaffPrincipal caller) {
        if (note == null || note.trim().length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give a reason — the person who raised this has to explain it to a customer.");
        }
        ApprovalRequest request = mustBePendingAndMine(requestId, caller);

        request.decide(ApprovalRequest.REJECTED, caller.staffId(), note.trim());
        decisions.save(new ApprovalDecision(request.getId(), "rejected",
                caller.staffId(), caller.role(), note.trim()));
        requests.save(request);

        audit.record("bmp_staff", caller.staffId(), "APPROVAL_REJECTED", "approval_request",
                request.getId(),
                Map.of("actionType", request.getActionType(), "ref", request.getRequestRef()),
                null, caller.email(), caller.role(), note.trim());

        log.info("{} rejected by {} ({}).", request.getRequestRef(), caller.email(), caller.role());
        return request;
    }

    /**
     * "Not mine either" — hand it further up.
     *
     * <p>Darshan: <i>"even if ops admin can't, they pass to admin."</i> The request stays PENDING
     * and changes addressee, so it is never bounced back to the person who raised it. Bouncing back
     * is how a customer waits two days for a decision nobody actually intended to refuse.
     */
    @Transactional
    public ApprovalRequest escalate(UUID requestId, String note, StaffPrincipal caller) {
        ApprovalRequest request = mustBePendingAndMine(requestId, caller);

        Optional<String> next = authority.nextApproverAbove(request.getActionType(), caller.role());
        if (next.isEmpty()) {
            /*
             * Top of the path. Refusing to escalate is the honest answer: pretending to pass it
             * upward when there is nobody above leaves it pending forever, addressed to a role that
             * will never look at it.
             */
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There's nobody above you for this. Approve it or reject it.");
        }

        decisions.save(new ApprovalDecision(request.getId(), "escalated",
                caller.staffId(), caller.role(), note));
        request.moveTo(next.get(), (short) (request.getCurrentStep() + 1));
        requests.save(request);

        audit.record("bmp_staff", caller.staffId(), "APPROVAL_ESCALATED", "approval_request",
                request.getId(),
                Map.of("actionType", request.getActionType(), "ref", request.getRequestRef(),
                        "toRole", next.get()),
                null, caller.email(), caller.role(), note);

        log.info("{} escalated by {} ({}) to {}.",
                request.getRequestRef(), caller.email(), caller.role(), next.get());
        return request;
    }

    /** The requester changed their mind, or the customer withdrew. Only they may do it. */
    @Transactional
    public ApprovalRequest cancel(UUID requestId, StaffPrincipal caller) {
        ApprovalRequest request = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (!request.getRequestedByStaffId().equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the person who raised this can withdraw it.");
        }
        if (!request.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This has already been " + request.getStatus() + ".");
        }
        request.decide(ApprovalRequest.CANCELLED, caller.staffId(), "Withdrawn by the requester.");
        return requests.save(request);
    }

    /** Everything waiting on my role, oldest first. Backed by {@code idx_approval_queue}. */
    @Transactional(readOnly = true)
    public List<ApprovalRequest> queueFor(String role) {
        return requests.findByCurrentApproverRoleAndStatusOrderByCreatedAtAsc(role, ApprovalRequest.PENDING);
    }

    /** What I asked for, and what happened to it. */
    @Transactional(readOnly = true)
    public List<ApprovalRequest> raisedBy(UUID staffId) {
        return requests.findByRequestedByStaffIdOrderByCreatedAtDesc(staffId);
    }

    /** The decision trail, for the detail view. */
    @Transactional(readOnly = true)
    public List<ApprovalDecision> trail(UUID requestId) {
        return decisions.findByRequestIdOrderByCreatedAtAsc(requestId);
    }

    /**
     * It must be pending, and addressed to MY role.
     *
     * <p>The role check is what stops a support agent approving a request that was routed past
     * them to ops — the whole ladder is meaningless if anybody can clear anything. Checked
     * server-side even though the queue only shows a person their own items, because a queue is a
     * view and this is the control.
     */
    private ApprovalRequest mustBePendingAndMine(UUID requestId, StaffPrincipal caller) {
        ApprovalRequest request = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));

        if (!request.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This has already been " + request.getStatus() + " — nothing more to decide.");
        }
        /*
         * super_admin can clear anything. Not a special case bolted on: the owner sits at the top
         * of every path, so a request that reached them is theirs by definition, and one that has
         * not yet is still something they are entitled to unblock at 2am.
         */
        boolean mine = request.getCurrentApproverRole().equalsIgnoreCase(caller.role())
                || "super_admin".equalsIgnoreCase(caller.role());
        if (!mine) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This is waiting on " + request.getCurrentApproverRole().replace('_', ' ')
                    + ", not on you.");
        }
        return request;
    }

    /**
     * The booking id inside an action's payload, or null.
     *
     * <h2>The one place generic code looks inside the payload, and why it is acceptable</h2>
     * Everywhere else the payload is opaque — that is what lets a new gated action be added without
     * touching this class. This is the exception, and it is narrow: ONE optional field, by one
     * agreed name, read only to apply a rule that must hold for every value-returning action.
     *
     * <p>The alternative was a {@code bookingId} column on {@code approval_request}, which would
     * have meant every non-booking action carrying a null column, and a migration the first time
     * something needed to be capped against a different thing. A convention that every capped
     * action includes {@code bookingId} is cheaper, and an action that omits it simply has no cap —
     * which {@code GoodwillCapService} treats as a legitimate case rather than a failure.
     *
     * <p>Never throws. A malformed payload is caught by the executor, which owns the shape; failing
     * here would refuse a request for a reason the person raising it cannot act on.
     */
    private UUID bookingIdFrom(String payloadJson) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
            var booking = node.get("bookingId");
            return booking == null || booking.isNull() ? null : UUID.fromString(booking.asText());
        } catch (Exception e) {
            log.debug("No usable bookingId in an approval payload ({}) — no booking cap applies.",
                    e.toString());
            return null;
        }
    }

    /** APR-000042. A sequence, not a row count — see the ticket-ref lesson in V008. */
    private String nextRef() {
        return String.format("APR-%06d", requests.nextRequestNumber());
    }
}
