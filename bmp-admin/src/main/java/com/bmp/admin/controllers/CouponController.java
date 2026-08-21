package com.bmp.admin.controllers;

import com.bmp.admin.client.RewardsServiceClient;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

    public CouponController(RewardsServiceClient rewards, AuditLogService audit) {
        this.rewards = rewards;
        this.audit = audit;
    }

    @Operation(summary = "What may I issue?", description = "Drives the console's form. Support sees a narrower one; admin sees everything.")
    @GetMapping("/limits")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public RewardsServiceClient.CouponLimits limits(@AuthenticationPrincipal StaffPrincipal caller) {
        return rewards.limits(caller.role());
    }

    @Operation(
        summary = "Issue a coupon",
        description = "Admin: any audience, any salon scope. Support: specific customers only, linked to a ticket, within value and validity caps. Enforced in bmp-rewards, not here.")
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
    public ResponseEntity<RewardsServiceClient.CouponDto> issue(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {

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

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
        summary = "List coupons",
        description = "Support sees only the coupons they issued — an agent browsing every marketing campaign is neither useful to them nor something to leave open by default. Admin sees all.")
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT_AGENT')")
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
}
