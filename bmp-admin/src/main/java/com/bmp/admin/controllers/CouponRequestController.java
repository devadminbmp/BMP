package com.bmp.admin.controllers;

import com.bmp.admin.client.RewardsServiceClient;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The console's view of coupon requests and support spend allowances.
 *
 * <h2>Where the authorization actually happens</h2>
 * Here. bmp-rewards cannot tell an ops admin from a support agent — it only sees the role string
 * bmp-admin hands it, which is why its internal controller is {@code ROLE_SERVICE} only. The
 * {@code @PreAuthorize} annotations below are the real gate.
 *
 * <p>The split that matters:
 * <ul>
 *   <li><b>Raising</b> a request — support, ops and superadmin. Anyone who can issue a coupon can
 *       ask for a bigger one.</li>
 *   <li><b>Deciding</b> one — ops and superadmin only. A support agent approving their own
 *       colleague's request would make the entire limit decorative, and approving their OWN
 *       would make it a formality.</li>
 *   <li><b>Allowances</b> — superadmin and ops. This is "how much can my team give away", which
 *       is a management decision, not a support one.</li>
 * </ul>
 *
 * <p>Every decision writes an audit entry. Approving a ₹3,000 coupon is exactly the kind of act
 * that needs a name against it — see {@code AuditLogService}.
 */
@Tag(name = "Coupon requests", description = "Approval queue for coupons above a support agent's limit, and for salon-owner promotional requests. Plus per-agent spend allowances.")
@RestController
@RequestMapping("/api/v1/admin/coupon-requests")
public class CouponRequestController {

    private final RewardsServiceClient rewards;
    private final AuditLogService audit;

    public CouponRequestController(RewardsServiceClient rewards, AuditLogService audit) {
        this.rewards = rewards;
        this.audit = audit;
    }

    public record RaiseBody(
        String proposedName,
        @NotBlank @Size(min = 20, max = 2000) String justification,
        UUID ticketId,
        String audienceType,
        String salonScope,
        String discountType,
        Long value,
        Long maxDiscountPaise,
        Long minSpendPaise,
        Integer perUserLimit,
        Integer totalUsageCap,
        Instant activeFrom,
        Instant activeTo,
        List<UUID> targetUserIds,
        List<UUID> targetSalonIds,
        Boolean salonFunded,
        UUID salonId
    ) {}

    public record DecisionBody(Long value, Long maxDiscountPaise, Instant activeTo, String note) {}

    public record AllowanceBody(
        UUID staffId, String staffEmail,
        Integer maxCountPerPeriod, Long maxPaisePerPeriod,
        Long maxFlatPaise, Integer maxPercentBps,
        @NotBlank @Size(min = 10, max = 1000) String reason,
        Instant expiresAt
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Operation(
        summary = "Ask for a coupon above your limit",
        description = "What a support agent does instead of hitting a wall. Justification is required and is read by whoever decides.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @PostMapping
    public ResponseEntity<RewardsServiceClient.CouponRequestDto> raise(
            @org.springframework.web.bind.annotation.RequestBody RaiseBody body,
            @AuthenticationPrincipal StaffPrincipal caller,
            jakarta.servlet.http.HttpServletRequest http) {

        Map<String, Object> req = new HashMap<>();
        put(req, "proposedName", body.proposedName());
        put(req, "justification", body.justification());
        put(req, "ticketId", body.ticketId());
        put(req, "audienceType", body.audienceType());
        put(req, "salonScope", body.salonScope());
        put(req, "discountType", body.discountType());
        put(req, "value", body.value());
        put(req, "maxDiscountPaise", body.maxDiscountPaise());
        put(req, "minSpendPaise", body.minSpendPaise());
        put(req, "perUserLimit", body.perUserLimit());
        put(req, "totalUsageCap", body.totalUsageCap());
        put(req, "activeFrom", body.activeFrom());
        put(req, "activeTo", body.activeTo());
        put(req, "targetUserIds", body.targetUserIds());
        put(req, "targetSalonIds", body.targetSalonIds());
        put(req, "salonFunded", body.salonFunded());

        RewardsServiceClient.CouponRequestDto created =
                rewards.raiseRequest(Map.of("request", req, "staff", staff(caller), "salonId", nullSafe(body.salonId())));

        audit.record("bmp_staff", caller.staffId(), "COUPON_REQUEST_RAISED", "coupon_request", created.id(),
                Map.of("ref", created.requestRef(), "value", nullSafe(body.value())),
                clientIp(http), caller.email(), caller.role(), body.justification());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "The pending queue", description = "Oldest first — there's an unhappy customer behind the oldest one.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @GetMapping("/queue")
    public List<RewardsServiceClient.CouponRequestDto> queue() {
        return rewards.requestQueue();
    }

    @Operation(summary = "Every request, newest first")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @GetMapping
    public List<RewardsServiceClient.CouponRequestDto> all() {
        return rewards.allRequests();
    }

    @Operation(summary = "My own requests", description = "What a support agent has asked for, and what came of it.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD','SUPPORT_AGENT')")
    @GetMapping("/mine")
    public List<RewardsServiceClient.CouponRequestDto> mine(@AuthenticationPrincipal StaffPrincipal caller) {
        return rewards.requestsByRequester(caller.staffId());
    }

    /**
     * Approve — ops and superadmin only.
     *
     * <p>Support is excluded deliberately, and this is the hinge of the whole design: if a
     * support agent could approve, two agents could approve each other's requests all day and
     * the limit would exist only on paper.
     */
    @Operation(
        summary = "Approve, optionally for less than was asked",
        description = "Leave value/maxDiscountPaise/activeTo null to grant exactly what was requested. The coupon is minted under YOUR identity — you are the one authorising it.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @PostMapping("/{requestId}/approve")
    public RewardsServiceClient.CouponRequestDto approve(
            @PathVariable UUID requestId,
            @org.springframework.web.bind.annotation.RequestBody DecisionBody body,
            @AuthenticationPrincipal StaffPrincipal caller,
            jakarta.servlet.http.HttpServletRequest http) {

        RewardsServiceClient.CouponRequestDto decided =
                rewards.approveRequest(requestId, Map.of("decision", decision(body), "staff", staff(caller)));

        audit.record("bmp_staff", caller.staffId(), "COUPON_REQUEST_APPROVED", "coupon_request", requestId,
                Map.of("ref", decided.requestRef(),
                       "grantedValue", decided.approvedValue() == null ? decided.value() : decided.approvedValue(),
                       "couponCode", nullSafe(decided.createdCouponCode())),
                clientIp(http), caller.email(), caller.role(), body.note());
        return decided;
    }

    @Operation(summary = "Reject, with a reason", description = "The reason is shown to the requester. Without one they'll simply ask again.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @PostMapping("/{requestId}/reject")
    public RewardsServiceClient.CouponRequestDto reject(
            @PathVariable UUID requestId,
            @org.springframework.web.bind.annotation.RequestBody DecisionBody body,
            @AuthenticationPrincipal StaffPrincipal caller,
            jakarta.servlet.http.HttpServletRequest http) {

        RewardsServiceClient.CouponRequestDto decided =
                rewards.rejectRequest(requestId, Map.of("decision", decision(body), "staff", staff(caller)));

        audit.record("bmp_staff", caller.staffId(), "COUPON_REQUEST_REJECTED", "coupon_request", requestId,
                Map.of("ref", decided.requestRef()),
                clientIp(http), caller.email(), caller.role(), body.note());
        return decided;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Allowances — "how much can my team give away"
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Operation(summary = "What I have left this period", description = "Shown before the issue form, so nobody discovers their limit by being refused mid-conversation.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/allowance/me")
    public RewardsServiceClient.AllowanceDto myAllowance(@AuthenticationPrincipal StaffPrincipal caller) {
        return rewards.allowance(caller.staffId());
    }

    @Operation(summary = "One agent's allowance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @GetMapping("/allowance/{staffId}")
    public RewardsServiceClient.AllowanceDto allowance(@PathVariable UUID staffId) {
        return rewards.allowance(staffId);
    }

    @Operation(summary = "Everyone with a bespoke allowance", description = "A set of exceptions nobody reviews quietly becomes the real policy — hence a screen for it.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @GetMapping("/allowance")
    public List<RewardsServiceClient.AllowanceOverrideDto> overrides() {
        return rewards.allowanceOverrides();
    }

    @Operation(summary = "Change what one agent may give away", description = "A reason is required. An expiry is strongly recommended — a temporary raise with no expiry becomes permanent.")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @PutMapping("/allowance")
    public RewardsServiceClient.AllowanceOverrideDto setAllowance(
            @org.springframework.web.bind.annotation.RequestBody AllowanceBody body,
            @AuthenticationPrincipal StaffPrincipal caller,
            jakarta.servlet.http.HttpServletRequest http) {

        Map<String, Object> o = new HashMap<>();
        put(o, "staffId", body.staffId());
        put(o, "staffEmail", body.staffEmail());
        put(o, "maxCountPerPeriod", body.maxCountPerPeriod());
        put(o, "maxPaisePerPeriod", body.maxPaisePerPeriod());
        put(o, "maxFlatPaise", body.maxFlatPaise());
        put(o, "maxPercentBps", body.maxPercentBps());
        put(o, "reason", body.reason());
        put(o, "expiresAt", body.expiresAt());

        RewardsServiceClient.AllowanceOverrideDto saved =
                rewards.setAllowance(Map.of("override", o, "staff", staff(caller)));

        audit.record("bmp_staff", caller.staffId(), "COUPON_ALLOWANCE_CHANGED", "bmp_staff", body.staffId(),
                Map.of("maxCount", nullSafe(body.maxCountPerPeriod()),
                       "maxPaise", nullSafe(body.maxPaisePerPeriod()),
                       "expiresAt", nullSafe(body.expiresAt())),
                clientIp(http), caller.email(), caller.role(), body.reason());
        return saved;
    }

    @Operation(summary = "Back to the platform default")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN')")
    @DeleteMapping("/allowance/{staffId}")
    public ResponseEntity<Void> clearAllowance(@PathVariable UUID staffId,
                                                @AuthenticationPrincipal StaffPrincipal caller,
                                                jakarta.servlet.http.HttpServletRequest http) {
        rewards.clearAllowance(staffId);
        audit.record("bmp_staff", caller.staffId(), "COUPON_ALLOWANCE_CLEARED", "bmp_staff", staffId,
                Map.of(), clientIp(http), caller.email(), caller.role(), "Reset to platform default");
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════

    private Map<String, Object> staff(StaffPrincipal caller) {
        Map<String, Object> s = new HashMap<>();
        put(s, "staffId", caller.staffId());
        put(s, "email", caller.email());
        put(s, "role", caller.role());
        put(s, "name", caller.email()); // bmp-admin's principal carries no display name
        return s;
    }

    private Map<String, Object> decision(DecisionBody body) {
        Map<String, Object> d = new HashMap<>();
        put(d, "value", body.value());
        put(d, "maxDiscountPaise", body.maxDiscountPaise());
        put(d, "activeTo", body.activeTo());
        put(d, "note", body.note());
        return d;
    }

    /** HashMap rather than Map.of because null values are meaningful here — "leave unchanged". */
    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v instanceof Instant i ? i.toString() : v);
    }

    /** Same caveat as elsewhere: X-Forwarded-For is caller-controlled until a trusted-proxy
     *  list is configured. Recorded to help a reader, not relied on as a control. */
    private static String clientIp(jakarta.servlet.http.HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : http.getRemoteAddr();
    }

    private static Object nullSafe(Object v) {
        return v == null ? "" : v;
    }
}
