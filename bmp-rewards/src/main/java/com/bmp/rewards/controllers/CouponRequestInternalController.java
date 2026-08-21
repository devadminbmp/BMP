package com.bmp.rewards.controllers;

import com.bmp.rewards.dto.CouponAdminDtos.StaffContext;
import com.bmp.rewards.dto.CouponRequestDtos.*;
import com.bmp.rewards.entities.CouponAllowanceOverride;
import com.bmp.rewards.entities.CouponRequest;
import com.bmp.rewards.repositories.CouponAllowanceOverrideRepository;
import com.bmp.rewards.services.CouponAllowanceService;
import com.bmp.rewards.services.CouponRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The STAFF side of the coupon request flow — called by bmp-admin, never by a browser.
 *
 * <p>{@code ROLE_SERVICE} only, at class level. The caller is bmp-admin presenting the internal
 * key: it has already authenticated the staff member and now passes their identity and role in
 * the body. Same indirection as {@link CouponAdminController}, for the same reason — every
 * staff action crosses exactly one place where the audit entry is written, and bmp-rewards
 * never has to understand staff sessions.
 *
 * <p><b>Who may approve is decided in bmp-admin, not here.</b> This service cannot distinguish
 * an ops admin from a support agent beyond the role string it is handed; the console's
 * {@code @PreAuthorize} is the real gate. That is a deliberate division and the reason this
 * controller is unreachable without the internal key.
 */
@Tag(name = "Coupon requests (internal)", description = "Staff-side request queue and allowance administration. Called by bmp-admin with the internal service credential.")
@RestController
@RequestMapping("/api/v1/internal/coupon-requests")
@PreAuthorize("hasRole('SERVICE')")
public class CouponRequestInternalController {

    private final CouponRequestService requests;
    private final CouponAllowanceService allowances;
    private final CouponAllowanceOverrideRepository overrides;

    public CouponRequestInternalController(CouponRequestService requests, CouponAllowanceService allowances,
                                            CouponAllowanceOverrideRepository overrides) {
        this.requests = requests;
        this.allowances = allowances;
        this.overrides = overrides;
    }

    /** What bmp-admin sends about the staff member acting. */
    public record StaffBody(UUID staffId, String email, String role, String name) {}

    public record RaiseByStaffRequest(@Valid RaiseRequest request, StaffBody staff, UUID salonId) {}
    public record DecideRequest(@Valid DecisionRequest decision, StaffBody staff) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Raising, as support
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Operation(
        summary = "A support agent asks for a coupon above their limit",
        description = """
            The escape hatch from CouponIssuePolicy. When an agent needs more than ₹500 / 20% / \
            one recipient, or has used up their rolling allowance, this is what they do instead \
            of hitting a 403 and telling the customer nothing can be done.""")
    @PostMapping
    public ResponseEntity<CouponRequestResponse> raise(@Valid @RequestBody RaiseByStaffRequest body) {
        RequesterContext who = new RequesterContext(
                CouponRequest.REQUESTER_STAFF,
                body.staff().staffId(),
                body.staff().name(),
                body.staff().email(),
                body.staff().role(),
                body.salonId());
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.raise(body.request(), who));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // The queue
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Operation(summary = "Pending requests, oldest first", description = "Oldest-first on purpose: a goodwill request has an unhappy customer already waiting behind it.")
    @GetMapping("/queue")
    public List<CouponRequestResponse> queue() {
        return requests.queue();
    }

    @Operation(summary = "All requests, newest first")
    @GetMapping
    public List<CouponRequestResponse> all() {
        return requests.all();
    }

    @Operation(summary = "One requester's own history")
    @GetMapping("/by-requester/{requesterId}")
    public List<CouponRequestResponse> byRequester(@PathVariable UUID requesterId) {
        return requests.mine(requesterId);
    }

    @Operation(summary = "How many are waiting", description = "For the console's overview tile — a queue nobody can see is a queue nobody works.")
    @GetMapping("/pending-count")
    public long pendingCount() {
        return requests.pendingCount();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Deciding
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Operation(
        summary = "Approve — optionally for less than was asked — and mint the coupon",
        description = """
            Leave `value`, `maxDiscountPaise` and `activeTo` null to grant exactly what was \
            requested; set any of them to grant something different. "You asked ₹2,000, here's \
            ₹800" is the answer most of the time, and an approver who can only say yes or no \
            says no.

            The coupon is created under the APPROVER's identity, so its provenance records who \
            authorised it rather than who asked — which is the honest attribution.""")
    @PostMapping("/{requestId}/approve")
    public CouponRequestResponse approve(@PathVariable UUID requestId, @Valid @RequestBody DecideRequest body) {
        return requests.approve(requestId, body.decision(), staffContext(body.staff()));
    }

    @Operation(summary = "Reject, with a reason", description = "The note is required and is shown to the requester. A refusal with no reason gets re-asked verbatim tomorrow.")
    @PostMapping("/{requestId}/reject")
    public CouponRequestResponse reject(@PathVariable UUID requestId, @Valid @RequestBody DecideRequest body) {
        return requests.reject(requestId, body.decision(), staffContext(body.staff()));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Allowances — "admin manages what support has"
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Operation(
        summary = "What this agent has left to give away",
        description = "Rendered BEFORE the issue form, not after a refusal — an agent who discovers their limit by being refused has already promised something to a customer.")
    @GetMapping("/allowance/{staffId}")
    public AllowanceResponse allowance(@PathVariable UUID staffId) {
        CouponAllowanceService.Allowance a = allowances.allowanceFor(staffId);
        return new AllowanceResponse(
                a.periodDays(), a.maxCount(), a.usedCount(), a.remainingCount(),
                a.maxPaise(), a.usedPaise(), a.remainingPaise(),
                a.exhausted(), a.hasOverride(), a.overrideReason());
    }

    @Operation(
        summary = "Give one agent a different allowance",
        description = """
            Raises or lowers the default for one person — a senior agent trusted with more, or \
            someone new who should have less for a fortnight. A reason is required, and an \
            `expiresAt` is strongly recommended: a temporary raise with no expiry becomes \
            permanent because nobody remembers to remove it.""")
    @Transactional
    @PutMapping("/allowance")
    public AllowanceOverrideResponse setAllowance(@Valid @RequestBody AllowanceOverrideBody body) {
        AllowanceOverrideRequest req = body.override();
        CouponAllowanceOverride o = overrides.findByStaffId(req.staffId())
                .orElseGet(() -> new CouponAllowanceOverride(req.staffId(), req.staffEmail(), req.reason()));
        o.setStaffEmail(req.staffEmail());
        o.setMaxCountPerPeriod(req.maxCountPerPeriod());
        o.setMaxPaisePerPeriod(req.maxPaisePerPeriod());
        o.setMaxFlatPaise(req.maxFlatPaise());
        o.setMaxPercentBps(req.maxPercentBps());
        o.setReason(req.reason());
        o.setExpiresAt(req.expiresAt());
        o.setUpdatedByStaffId(body.staff().staffId());
        o.setUpdatedByEmail(body.staff().email());
        o.touch();
        o = overrides.save(o);
        return toOverrideResponse(o);
    }

    public record AllowanceOverrideBody(@Valid AllowanceOverrideRequest override, StaffBody staff) {}

    @Operation(
        summary = "Everyone with a bespoke allowance",
        description = "Worth a screen of its own: a set of exceptions nobody ever reviews stops being a set of exceptions and quietly becomes the real policy.")
    @GetMapping("/allowance")
    public List<AllowanceOverrideResponse> listOverrides() {
        return overrides.findAllByOrderByCreatedAtDesc().stream().map(this::toOverrideResponse).toList();
    }

    @Operation(summary = "Remove an override", description = "Puts that agent back on the platform default.")
    @DeleteMapping("/allowance/{staffId}")
    @Transactional
    public ResponseEntity<Void> clearAllowance(@PathVariable UUID staffId) {
        overrides.findByStaffId(staffId).ifPresent(overrides::delete);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════

    private StaffContext staffContext(StaffBody s) {
        return new StaffContext(s.staffId(), s.email(), s.role());
    }

    private AllowanceOverrideResponse toOverrideResponse(CouponAllowanceOverride o) {
        return new AllowanceOverrideResponse(
                o.getId(), o.getStaffId(), o.getStaffEmail(),
                o.getMaxCountPerPeriod(), o.getMaxPaisePerPeriod(),
                o.getMaxFlatPaise(), o.getMaxPercentBps(),
                o.getReason(), o.getExpiresAt(),
                o.getUpdatedByStaffId(), o.getUpdatedByEmail(),
                o.getCreatedAt(), o.getUpdatedAt());
    }
}
