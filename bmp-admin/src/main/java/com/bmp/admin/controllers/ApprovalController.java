package com.bmp.admin.controllers;

import com.bmp.admin.entities.ApprovalRequest;
import com.bmp.admin.entities.AuthorityLimit;
import com.bmp.admin.security.SupportTier;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.ApprovalRequestService;
import com.bmp.admin.services.AuthorityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Approvals — the one door for every action somebody isn't allowed to do alone. Session 58.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THERE IS ONE CONTROLLER AND NOT ONE PER ACTION
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Darshan: <i>"I've given the example only for discount coupons — next it can be anything."</i>
 *
 * <p>So a coupon approval, a refund approval and a salon suspension all arrive here. The console
 * gets ONE queue screen that works for actions nobody has written yet, and a support lead learns
 * one interface rather than one per power they are given.
 *
 * <p>Adding a gated action touches: a row in {@code authority_limit}, and a class implementing
 * {@code ApprovalActionExecutor}. Not this file.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * AUTHORISATION IS IN THE SERVICE, NOT THE ANNOTATION
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code @PreAuthorize} here says only "you are staff on the ladder". It cannot express "you may
 * approve this particular request because it is addressed to your role and is within your ceiling"
 * — that is data, and it is checked in {@code ApprovalRequestService}. The annotation is the outer
 * door; the real control is one layer in, which is the same split every salon-scoped endpoint in
 * this platform uses.
 */
@Tag(name = "Approvals",
     description = "Raise, approve, reject and escalate anything above your own authority. One generic flow for coupons, refunds, waivers, suspensions and erasures — see V010 for the matrix.")
@RestController
@RequestMapping("/api/v1/admin/approvals")
public class ApprovalController {

    private final ApprovalRequestService approvals;
    private final AuthorityService authority;
    /** Changing a band is among the most consequential settings changes there is; it is audited. */
    private final com.bmp.admin.services.AuditLogService audit;

    public ApprovalController(ApprovalRequestService approvals, AuthorityService authority,
                               com.bmp.admin.services.AuditLogService audit) {
        this.approvals = approvals;
        this.authority = authority;
        this.audit = audit;
    }

    // ── shapes ────────────────────────────────────────────────────────────────────────────────

    public record ApprovalResponse(
            UUID id, String requestRef, String actionType, long valuePaise,
            String status, String currentApproverRole, short currentStep,
            UUID ticketId, String justification,
            UUID requestedByStaffId, String requestedByRole,
            String decisionNote, String executionError,
            Instant createdAt, Instant decidedAt, Instant executedAt) {

        static ApprovalResponse of(ApprovalRequest r) {
            return new ApprovalResponse(r.getId(), r.getRequestRef(), r.getActionType(),
                    r.getValuePaise(), r.getStatus(), r.getCurrentApproverRole(), r.getCurrentStep(),
                    r.getTicketId(), r.getJustification(), r.getRequestedByStaffId(),
                    r.getRequestedByRole(), r.getDecisionNote(), r.getExecutionError(),
                    r.getCreatedAt(), r.getDecidedAt(), r.getExecutedAt());
        }
    }

    /**
     * @param payload action-specific, opaque here. Only the executor for this action_type reads it.
     * @param valuePaise 0 for actions with no amount — the matrix still routes correctly, because a
     *                   0 ceiling means "never, at any amount".
     */
    public record RaiseRequest(
            @NotBlank @Size(max = 60) String actionType,
            @NotBlank String payload,
            long valuePaise,
            UUID ticketId,
            @NotBlank @Size(max = 2000) String justification) {}

    public record DecisionRequest(@Size(max = 2000) String note) {}

    /**
     * What a role may do, so the console can show limits BEFORE somebody types a number.
     *
     * @param role WHOSE band this is. On {@code /my-authority} it is always the caller's own, which
     *             is why it was originally omitted — but {@code /matrix} returns every role's cell
     *             and without this field the rows are indistinguishable, so the owner's editor
     *             could not tell which ceiling it was about to change. Added Session 59.
     * @param approverRole who signs off ABOVE this band. A different question from {@code role},
     *                     and conflating the two is the mistake this javadoc exists to prevent.
     */
    public record LimitResponse(String actionType, String role, Long maxValuePaise,
                                 String approverRole, boolean requiresTicket) {
        static LimitResponse of(AuthorityLimit a) {
            return new LimitResponse(a.getActionType(), a.getRole(), a.getMaxValuePaise(),
                    a.getApproverRole(), a.isRequiresTicket());
        }
    }

    // ── what am I allowed to do? ──────────────────────────────────────────────────────────────

    /**
     * My own authority, for every action.
     *
     * <h2>Why the UI asks before it offers</h2>
     * A support agent should see "Give a coupon (up to ₹500)" rather than a blank field that
     * refuses ₹800 after they have typed it and told the customer. The button and its caption both
     * come from here.
     *
     * <p>It is a courtesy, not a control — every write re-checks server-side.
     */
    @Operation(summary = "What my role may do, and up to how much")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/my-authority")
    public List<LimitResponse> myAuthority(@AuthenticationPrincipal StaffPrincipal caller) {
        return authority.limitsForRole(caller.role()).stream().map(LimitResponse::of).toList();
    }

    /**
     * Check one specific amount before committing to it.
     *
     * <p>Lets a form say "this needs a lead's approval" as the number is typed, so the person
     * writes their justification once instead of being refused and starting again.
     */
    @Operation(summary = "Would this be allowed, or does it need approval?")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/check")
    public AuthorityService.Decision check(@RequestParam String actionType,
                                            @RequestParam(defaultValue = "0") long valuePaise,
                                            @AuthenticationPrincipal StaffPrincipal caller) {
        return authority.check(actionType, caller.role(), valuePaise);
    }

    // ── the owner sets the bands ──────────────────────────────────────────────────────────────

    /** @param maxValuePaise null = no ceiling; 0 = may request but never act alone. */
    public record UpdateLimitRequest(
            @NotBlank String actionType,
            @NotBlank String role,
            Long maxValuePaise) {}

    /**
     * The whole matrix, every action and every role.
     *
     * <p>Readable by ops and finance as well as the owner: knowing where the ceilings sit is how a
     * lead decides whether to approve or pass up, and hiding it would leave people guessing at the
     * rule they are being judged against.
     */
    @Operation(summary = "The whole authority matrix")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','FINANCE_ADMIN')")
    @GetMapping("/matrix")
    public List<LimitResponse> matrix() {
        return authority.allActionTypes().stream()
                .flatMap(a -> authority.pathFor(a).stream())
                .map(LimitResponse::of)
                .toList();
    }

    /**
     * Change a band. Darshan: <i>"all ranges can be fixed by admin, owner of BMP."</i>
     *
     * <h2>SUPER_ADMIN only, deliberately not ops</h2>
     * An ops admin who can raise their own ceiling has no ceiling, and the matrix becomes advisory
     * the moment somebody senior enough to be inconvenienced by it can edit it. Same reasoning as a
     * salon owner not setting their own commission (Session 45).
     *
     * <p>The service refuses a band above the one it escalates to — otherwise escalating would make
     * approval LESS likely, and the ladder would stop meaning anything.
     */
    @Operation(summary = "Change what a role may do alone",
               description = "Platform owner only. A band can never exceed the band it escalates to — raise the one above first. Audited.")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/matrix")
    public LimitResponse updateLimit(@Valid @RequestBody UpdateLimitRequest req,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        AuthorityLimit updated = authority.updateLimit(
                req.actionType(), req.role(), req.maxValuePaise(), caller.staffId());

        audit.record("bmp_staff", caller.staffId(), "AUTHORITY_LIMIT_CHANGED", "authority_limit",
                updated.getId(),
                java.util.Map.of("actionType", req.actionType(), "role", req.role(),
                        "newLimitPaise", String.valueOf(req.maxValuePaise())),
                null, caller.email(), caller.role(), null);

        return LimitResponse.of(updated);
    }

    // ── raise, and track ──────────────────────────────────────────────────────────────────────

    @Operation(summary = "Ask for approval to do something above your limit",
               description = "409 if you could have done it yourself; 403 if nobody at your role may ever do it. Support goodwill must cite a ticket — see authority_limit.requires_ticket.")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT','SUPPORT_LEAD','ADMIN','OPS_ADMIN','FINANCE_ADMIN','SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<ApprovalResponse> raise(@Valid @RequestBody RaiseRequest req,
                                                    @AuthenticationPrincipal StaffPrincipal caller) {
        ApprovalRequest created = approvals.raise(
                req.actionType(), req.payload(), req.valuePaise(), req.ticketId(),
                req.justification(), caller, tierOf(caller.role()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalResponse.of(created));
    }

    @Operation(summary = "What I've asked for, and what happened to it")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/mine")
    public List<ApprovalResponse> mine(@AuthenticationPrincipal StaffPrincipal caller) {
        return approvals.raisedBy(caller.staffId()).stream().map(ApprovalResponse::of).toList();
    }

    /**
     * Everything waiting on MY role.
     *
     * <p>Scoped from the token, not a parameter — a role query string would let a support agent
     * page through the owner's pending decisions, which is a list of every large sum the business
     * is about to move.
     */
    @Operation(summary = "My approval queue")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','FINANCE_ADMIN','SUPER_ADMIN')")
    @GetMapping("/queue")
    public List<ApprovalResponse> queue(@AuthenticationPrincipal StaffPrincipal caller) {
        return approvals.queueFor(caller.role()).stream().map(ApprovalResponse::of).toList();
    }

    // ── decide ────────────────────────────────────────────────────────────────────────────────

    @Operation(summary = "Approve it — and carry it out",
               description = "The action executes immediately. If execution fails the request is marked failed WITH the approval kept, so it can be retried without being re-approved.")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','FINANCE_ADMIN','SUPER_ADMIN')")
    @PostMapping("/{id}/approve")
    public ApprovalResponse approve(@PathVariable UUID id,
                                     @RequestBody(required = false) DecisionRequest req,
                                     @AuthenticationPrincipal StaffPrincipal caller) {
        return ApprovalResponse.of(
                approvals.approve(id, req == null ? null : req.note(), caller));
    }

    @Operation(summary = "Refuse it",
               description = "A reason is required — the person who raised this has to explain it to a customer.")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','FINANCE_ADMIN','SUPER_ADMIN')")
    @PostMapping("/{id}/reject")
    public ApprovalResponse reject(@PathVariable UUID id,
                                    @Valid @RequestBody DecisionRequest req,
                                    @AuthenticationPrincipal StaffPrincipal caller) {
        return ApprovalResponse.of(approvals.reject(id, req.note(), caller));
    }

    /**
     * "Not mine either" — pass it further up.
     *
     * <p>Darshan: <i>"even if ops admin can't, they pass to admin."</i> The request stays pending
     * and changes addressee; it is never bounced back to the requester, because bouncing back is
     * how a customer waits two days for a decision nobody meant to refuse.
     */
    @Operation(summary = "Pass it to the next authority above you")
    @PreAuthorize("hasAnyRole('SUPPORT_LEAD','ADMIN','OPS_ADMIN','FINANCE_ADMIN','SUPER_ADMIN')")
    @PostMapping("/{id}/escalate")
    public ApprovalResponse escalate(@PathVariable UUID id,
                                      @RequestBody(required = false) DecisionRequest req,
                                      @AuthenticationPrincipal StaffPrincipal caller) {
        return ApprovalResponse.of(
                approvals.escalate(id, req == null ? null : req.note(), caller));
    }

    @Operation(summary = "Withdraw a request you raised")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/cancel")
    public ApprovalResponse cancel(@PathVariable UUID id,
                                    @AuthenticationPrincipal StaffPrincipal caller) {
        return ApprovalResponse.of(approvals.cancel(id, caller));
    }

    /** The decision trail — who declined, who approved, and what they said. */
    @Operation(summary = "Every decision made on this request")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/trail")
    public List<TrailEntry> trail(@PathVariable UUID id) {
        return approvals.trail(id).stream()
                .map(d -> new TrailEntry(d.getDecision(), d.getDecidedByRole(), d.getNote(),
                        d.getCreatedAt()))
                .toList();
    }

    /** Deliberately no staff id or email — a trail is about roles and reasons, not names. */
    public record TrailEntry(String decision, String byRole, String note, Instant at) {}

    /**
     * Role → tier for the approval trail.
     *
     * <p>Read from the TOKEN rather than the staff row on purpose: this path already has the
     * caller's role, and a database read to learn something we were just told is a round trip for
     * nothing.
     *
     * <p>Session 65 — this used to be a hand-written switch here, which is how it came to be missing
     * the new {@code admin} role the day that role was added (an admin would have been recorded on
     * the approval trail as tier 0 by accident rather than by decision). It now defers to
     * {@link SupportTier}, the same mapping the staff row itself is written from, so the trail and
     * the row cannot disagree.
     */
    private static short tierOf(String role) {
        return SupportTier.forRole(role);
    }
}
