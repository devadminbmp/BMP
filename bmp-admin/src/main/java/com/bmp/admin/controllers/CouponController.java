package com.bmp.admin.controllers;

import com.bmp.admin.client.RewardsServiceClient;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The console's coupon endpoints.
 *
 * <p>A thin, deliberate layer: authenticate the staff member, forward to bmp-rewards with their
 * identity and role attached, and write the audit entry. The interesting rules — what admin may
 * issue versus support — live in bmp-rewards' {@code CouponIssuePolicy}, next to the data they
 * protect, so they cannot be bypassed by any other caller.
 *
 * <p><b>Coupons are money.</b> Every issue and every status change is audited with the actor's
 * name, role and the ticket it settles, because "what did we spend on goodwill last month, and
 * on what" should be a query rather than a guess.
 */
@Tag(name = "Coupons", description = "Issue and manage discount coupons. Admin can create campaigns; support can only issue to specific customers against a raised ticket.")
@RestController
@RequestMapping("/api/v1/admin/coupons")
public class CouponController {

    private final RewardsServiceClient rewards;
    private final AuditLogService audit;
    /** Session 59 — nobody gives back more than the customer paid. See GoodwillCapService. */
    private final com.bmp.admin.services.GoodwillCapService goodwillCap;

    public CouponController(RewardsServiceClient rewards, AuditLogService audit,
                             com.bmp.admin.services.GoodwillCapService goodwillCap) {
        this.rewards = rewards;
        this.audit = audit;
        this.goodwillCap = goodwillCap;
    }

    @Operation(summary = "What may I issue?", description = "Drives the console's form. Support sees a narrower one; admin sees everything.")
    @GetMapping("/limits")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public RewardsServiceClient.CouponLimits limits(@AuthenticationPrincipal StaffPrincipal caller) {
        return rewards.limits(caller.role());
    }

    /**
     * The coupon's value in paise, from a loosely-typed request body.
     *
     * <p>Returns 0 — meaning "no cap can be applied" — rather than throwing on a malformed value.
     * bmp-rewards validates the field properly and will reject it with a message about the value;
     * failing here would produce a confusing complaint about a booking limit instead.
     */
    private static long asPaise(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * How much can still be given on this booking, and what has already been. Session 60.
     *
     * <h2>Asked BEFORE a number is typed, not after</h2>
     * The cap has always been enforced — but only at submit, which means the agent finds out they
     * cannot offer ₹500 immediately after telling the customer they can. Same reasoning as
     * {@code /approvals/my-authority}: show the ceiling before somebody promises past it.
     *
     * <p>{@code remainingPaise} is null when there is no booking to measure against, which the
     * console renders as "no booking limit" rather than as zero. Zero and "not applicable" are
     * opposite answers and showing the wrong one either blocks legitimate goodwill or implies a
     * limit that is not there.
     */
    @Operation(summary = "What's left to give on this booking",
               description = "A hint for the form, not a control — issuing re-checks server-side. Includes what was already given, so the number explains itself.")
    @GetMapping("/goodwill-context")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT','SUPPORT_LEAD','ADMIN','OPS_ADMIN','FINANCE_ADMIN','SUPER_ADMIN')")
    public com.bmp.admin.services.GoodwillCapService.GoodwillContext goodwillContext(
            @RequestParam java.util.UUID bookingId) {
        return goodwillCap.contextFor(bookingId);
    }

    @Operation(
        summary = "Issue a coupon",
        description = "Admin: any audience, any salon scope. Support: specific customers only, linked to a ticket, within value and validity caps. Enforced in bmp-rewards, not here.")
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public ResponseEntity<RewardsServiceClient.CouponDto> issue(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {

        /*
         * Session 59 — THE DIRECT PATH NEEDS THE CAP TOO.
         *
         * The approval flow checks it twice, but a coupon inside somebody's own limit never becomes
         * an approval request at all — it comes straight through here. Without this, a support
         * agent could give ₹400 of goodwill on a ₹100 booking, entirely within their ₹500 band and
         * entirely against the rule.
         *
         * The cap applies to EVERY role on this endpoint, including super_admin: "more than the
         * customer paid" is not a seniority question.
         */
        Object bookingRef = request.get("bookingId");
        if (bookingRef != null) {
            goodwillCap.assertWithinBookingValue("coupon.issue",
                    java.util.UUID.fromString(bookingRef.toString()),
                    asPaise(request.get("value")));
        }

        RewardsServiceClient.CouponDto created =
                rewards.issue(request, caller.staffId(), caller.email(), caller.role());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("code", created.code());
        metadata.put("audienceType", created.audienceType());
        metadata.put("discountType", created.discountType());
        metadata.put("value", created.value());
        metadata.put("recipients", created.targetUserIds() == null ? 0 : created.targetUserIds().size());
        metadata.put("ticketId", String.valueOf(created.issuedForTicketId()));

        audit.record("bmp_staff", caller.staffId(), "COUPON_ISSUED", "coupon", created.id(),
                metadata, clientIp(http), caller.email(), caller.role(), created.issueReason());

        /*
         * Session 59 (V012) — count it against the booking, AFTER it succeeded.
         *
         * The check above asks "is this within what the customer paid"; this is the other half —
         * remembering that it happened, so the NEXT coupon on the same booking sees it. Without
         * this the cap resets on every request and a ₹600 booking takes ₹500 coupons all day.
         *
         * Deliberately after `rewards.issue` returned, and deliberately non-fatal: the customer has
         * the coupon by now, and failing the response here would have an agent issue it twice.
         */
        if (bookingRef != null) {
            goodwillCap.record("coupon.issue",
                    java.util.UUID.fromString(bookingRef.toString()),
                    asPaise(request.get("value")),
                    null, // no approval request — this was inside the caller's own band
                    caller.staffId(), caller.role(), created.code());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
        summary = "List coupons",
        description = "Support sees only the coupons they issued — an agent browsing every marketing campaign is neither useful to them nor something to leave open by default. Admin sees all.")
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public List<RewardsServiceClient.CouponDto> list(
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal StaffPrincipal caller) {

        List<RewardsServiceClient.CouponDto> all = rewards.list(status);
        if (StaffPermission.has(caller.role(), StaffPermission.SETTINGS_MANAGE)) {
            return all;   // ops/superadmin
        }
        return all.stream()
                .filter(c -> caller.email().equalsIgnoreCase(c.createdByEmail()))
                .toList();
    }

    @Operation(summary = "Pause, resume or revoke", description = "Support may only change coupons they issued themselves — enforced in bmp-rewards too.")
    @PutMapping("/{couponId}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    public RewardsServiceClient.CouponDto setStatus(
            @PathVariable UUID couponId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {

        String status = body.get("status");
        if (status == null || status.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }

        RewardsServiceClient.CouponDto updated =
                rewards.setStatus(couponId, Map.of("status", status), caller.staffId(), caller.email(), caller.role());

        audit.record("bmp_staff", caller.staffId(), "COUPON_STATUS_CHANGED", "coupon", couponId,
                Map.of("status", status, "code", updated.code()),
                clientIp(http), caller.email(), caller.role(), body.get("reason"));

        return updated;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  REFERRAL PROGRAMME — what the platform pays, set here rather than in a config file. Session 64.
    // ══════════════════════════════════════════════════════════════════════════════════════════
    /*
     * The amounts used to be Spring properties, so changing them needed an env var, a redeploy and
     * a developer. A commercial lever only engineering can pull is a lever nobody pulls.
     *
     * SUPER_ADMIN and FINANCE_ADMIN only. This is the platform committing its own money to every
     * future referral — ops and support have no business setting it, and the blast radius of a
     * mistyped amount is every referral from now until somebody notices.
     */

    @Operation(summary = "The referral offer in force now")
    @GetMapping("/referral-program")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')")
    public com.bmp.admin.client.RewardsServiceClient.ReferralProgramView referralProgram() {
        return rewards.currentReferralProgram();
    }

    @Operation(summary = "Every version of the offer, newest first",
               description = "Append-only. What was promised, when, and by whom — a single mutable settings row could not answer that.")
    @GetMapping("/referral-program/history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')")
    public java.util.List<com.bmp.admin.client.RewardsServiceClient.ReferralProgramView> referralProgramHistory() {
        return rewards.referralProgramHistory();
    }

    public record PublishReferralProgramRequest(
            long referrerRewardPaise, long refereeRewardPaise,
            boolean referrerEnabled, boolean refereeEnabled,
            java.time.Instant effectiveFrom,
            @jakarta.validation.constraints.NotBlank String note) {}

    @Operation(summary = "Change the referral offer",
               description = "Applies to referrals made AFTER this. Referrals already recorded keep the amounts frozen onto them and will still pay out.")
    @PostMapping("/referral-program")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')")
    public com.bmp.admin.client.RewardsServiceClient.ReferralProgramView publishReferralProgram(
            @Valid @RequestBody PublishReferralProgramRequest req,
            @AuthenticationPrincipal StaffPrincipal caller) {

        var published = rewards.publishReferralProgram(
                new com.bmp.admin.client.RewardsServiceClient.PublishReferralProgram(
                        req.referrerRewardPaise(), req.refereeRewardPaise(),
                        req.referrerEnabled(), req.refereeEnabled(),
                        req.effectiveFrom(), caller.staffId(), caller.email(), req.note()));

        /*
         * Audited HERE, not in bmp-rewards.
         *
         * bmp-rewards has no staff tokens to inspect and no business knowing what a support lead is.
         * bmp-admin has already authenticated the person, so this is the one place that can record
         * WHO changed what the platform pays — which is the entire reason the console goes through
         * bmp-admin rather than calling bmp-rewards directly.
         *
         * The version row in rewards_schema is the durable record of the VALUE; this is the record
         * of the PERSON. Both matter, and they live in different services for good reasons.
         */
        audit.record("bmp_staff", caller.staffId(), "REFERRAL_PROGRAM_CHANGED",
                "referral_program", published.id(),
                java.util.Map.of(
                        "referrerRewardPaise", String.valueOf(req.referrerRewardPaise()),
                        "refereeRewardPaise", String.valueOf(req.refereeRewardPaise()),
                        "referrerEnabled", String.valueOf(req.referrerEnabled()),
                        "refereeEnabled", String.valueOf(req.refereeEnabled())),
                null, caller.email(), caller.role(), req.note());

        return published;
    }

}
