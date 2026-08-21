package com.bmp.rewards.controllers;

import com.bmp.rewards.dto.CouponAdminDtos.*;
import com.bmp.rewards.services.CouponAdminService;
import com.bmp.rewards.services.CouponIssuePolicy;
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
 * Internal coupon administration — called by bmp-admin, never by a browser.
 *
 * <p>{@code ROLE_SERVICE} only: the caller is bmp-admin presenting the internal service key,
 * which has already authenticated the staff member and now passes their identity and role in
 * the body. That indirection is deliberate — it means every coupon issued crosses exactly one
 * place where the audit entry is written, and bmp-rewards never has to understand staff
 * sessions.
 *
 * <p>The admin/support split is enforced in {@link CouponIssuePolicy}, not here: an endpoint
 * annotation cannot express "support may only issue to specific users, tied to a ticket, under
 * a value cap".
 */
@Tag(name = "Coupon administration (internal)", description = "Staff-issued coupons. Called by bmp-admin with the internal service credential; the staff member's role decides what they may issue.")
@RestController
@RequestMapping("/api/v1/internal/coupons")
@PreAuthorize("hasRole('SERVICE')")
public class CouponAdminController {

    private final CouponAdminService service;

    public CouponAdminController(CouponAdminService service) {
        this.service = service;
    }

    @Operation(
        summary = "What may this role issue?",
        description = "The console renders its form from this instead of hardcoding limits — so raising a support cap in the database changes the UI without a deploy, and a support agent never fills in a form that's going to be refused.")
    @GetMapping("/limits")
    public CouponLimitsResponse limits(@RequestParam String role) {
        CouponIssuePolicy.Limits limits = service.limitsFor(role);
        return new CouponLimitsResponse(
                limits.unrestricted(),
                limits.maxFlatPaise(),
                limits.maxPercentBasisPoints(),
                limits.maxValidityDays(),
                limits.maxRecipients(),
                limits.requiresTicket(),
                limits.unrestricted()
                        ? CouponIssuePolicy.AUDIENCE_TYPES
                        : List.of(CouponIssuePolicy.AUDIENCE_SELECTED_USERS));
    }

    @Operation(
        summary = "Issue a coupon",
        description = """
            ADMIN (super_admin / ops_admin) may issue anything: all users, selected users, new \
            users, referred users; all salons or a chosen list.

            SUPPORT (support_agent) may only issue to SPECIFIC named customers, must link it to \
            the ticket it settles, and is capped on value, recipient count and validity — all \
            configurable in rewards_schema.coupon_policy.

            The reason is that these are two different activities sharing a table: admin runs \
            campaigns with a budget, support settles individual complaints. An agent who could \
            issue an all-users coupon could, with one mistyped form, give the entire customer \
            base 50% off — not maliciously, which is exactly the point.""")
    @PostMapping
    public ResponseEntity<CouponResponse> issue(
            @Valid @RequestBody CreateCouponRequest req,
            @RequestParam UUID staffId,
            @RequestParam String staffEmail,
            @RequestParam String staffRole) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.issue(req, new StaffContext(staffId, staffEmail, staffRole)));
    }

    @Operation(summary = "List coupons", description = "Newest first, optionally filtered by status.")
    @GetMapping
    public List<CouponResponse> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @Operation(
        summary = "Pause, resume or revoke",
        description = "Pausing stops redemption immediately WITHOUT deleting the coupon — customers holding the code get a clear refusal rather than a code that has silently vanished. Support may only change coupons they issued themselves; stopping a live marketing campaign is a lot of damage for one misclick.")
    @PutMapping("/{couponId}/status")
    public CouponResponse setStatus(
            @PathVariable UUID couponId,
            @Valid @RequestBody SetCouponStatusRequest req,
            @RequestParam UUID staffId,
            @RequestParam String staffEmail,
            @RequestParam String staffRole) {
        return service.setStatus(couponId, req.status(), new StaffContext(staffId, staffEmail, staffRole));
    }
}
