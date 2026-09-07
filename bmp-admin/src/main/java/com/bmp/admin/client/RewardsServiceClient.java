package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls bmp-rewards for coupon administration, with the internal service credential.
 *
 * <p><b>Why the console goes through bmp-admin instead of calling bmp-rewards directly:</b> so
 * that every coupon a staff member issues crosses exactly one place where an audit entry is
 * written. If the console called bmp-rewards itself, coupons — which are money — would be
 * issued with no record of who did it, and bmp-rewards would need to understand staff sessions
 * and roles, which it has no business knowing about.
 *
 * <p>The staff member's identity and role travel as query parameters because bmp-rewards has no
 * staff tokens to inspect; bmp-admin has already authenticated them.
 *
 * <p>Records here are LOCAL MIRRORS of bmp-rewards' DTOs, deliberately loose (Map for the
 * request) so a new optional field over there doesn't require a coordinated deploy.
 */
@FeignClient(name = "bmp-rewards-service", configuration = com.bmp.admin.config.FeignInternalKeyConfig.class)
public interface RewardsServiceClient {

    record CouponLimits(
        boolean unrestricted, long maxFlatPaise, int maxPercentBasisPoints,
        int maxValidityDays, int maxRecipients, boolean requiresTicket,
        List<String> allowedAudienceTypes
    ) {}

    record CouponDto(
        UUID id, String code, String name, String description, String couponType,
        String audienceType, String salonScope, String status,
        String discountType, long value, Long maxDiscountPaise, long minSpendPaise,
        int perUserLimit, int totalUsageCap, Instant activeFrom, Instant activeTo,
        String commissionBase, List<UUID> targetUserIds, List<UUID> targetSalonIds,
        String createdByEmail, String createdByRole, UUID issuedForTicketId, String issueReason,
        long timesUsed, Instant createdAt
    ) {}

    @GetMapping("/api/v1/internal/coupons/limits")
    CouponLimits limits(@RequestParam("role") String role);

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Session 31 — the request/approval workflow and spend allowances.
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** Mirrors bmp-rewards' CouponRequestDtos.CouponRequestResponse. */
    record CouponRequestDto(
        UUID id, String requestRef, String status,
        String requesterType, UUID requesterId, String requesterName, String requesterEmail,
        String requesterRole, UUID salonId,
        String justification, UUID issuedForTicketId,
        String proposedName, String audienceType, String salonScope,
        String discountType, long value, Long maxDiscountPaise,
        long minSpendPaise, int perUserLimit, Integer totalUsageCap,
        Instant activeFrom, Instant activeTo, String commissionBase,
        int recipientCount,
        Long approvedValue, Long approvedMaxDiscountPaise, Instant approvedActiveTo,
        UUID decidedByStaffId, String decidedByEmail, Instant decidedAt, String decisionNote,
        UUID createdCouponId, String createdCouponCode,
        Instant createdAt, Instant updatedAt
    ) {}

    record AllowanceDto(
        int periodDays,
        int maxCount, int usedCount, int remainingCount,
        long maxPaise, long usedPaise, long remainingPaise,
        boolean exhausted, boolean hasOverride, String overrideReason
    ) {}

    record AllowanceOverrideDto(
        UUID id, UUID staffId, String staffEmail,
        Integer maxCountPerPeriod, Long maxPaisePerPeriod,
        Long maxFlatPaise, Integer maxPercentBps,
        String reason, Instant expiresAt,
        UUID updatedByStaffId, String updatedByEmail,
        Instant createdAt, Instant updatedAt
    ) {}

    @PostMapping("/api/v1/internal/coupon-requests")
    CouponRequestDto raiseRequest(@RequestBody Map<String, Object> body);

    @GetMapping("/api/v1/internal/coupon-requests/queue")
    List<CouponRequestDto> requestQueue();

    @GetMapping("/api/v1/internal/coupon-requests")
    List<CouponRequestDto> allRequests();

    @GetMapping("/api/v1/internal/coupon-requests/by-requester/{requesterId}")
    List<CouponRequestDto> requestsByRequester(@PathVariable("requesterId") UUID requesterId);

    @GetMapping("/api/v1/internal/coupon-requests/pending-count")
    long pendingRequestCount();

    @PostMapping("/api/v1/internal/coupon-requests/{requestId}/approve")
    CouponRequestDto approveRequest(@PathVariable("requestId") UUID requestId,
                                     @RequestBody Map<String, Object> body);

    @PostMapping("/api/v1/internal/coupon-requests/{requestId}/reject")
    CouponRequestDto rejectRequest(@PathVariable("requestId") UUID requestId,
                                    @RequestBody Map<String, Object> body);

    @GetMapping("/api/v1/internal/coupon-requests/allowance/{staffId}")
    AllowanceDto allowance(@PathVariable("staffId") UUID staffId);

    @GetMapping("/api/v1/internal/coupon-requests/allowance")
    List<AllowanceOverrideDto> allowanceOverrides();

    @PutMapping("/api/v1/internal/coupon-requests/allowance")
    AllowanceOverrideDto setAllowance(@RequestBody Map<String, Object> body);

    @DeleteMapping("/api/v1/internal/coupon-requests/allowance/{staffId}")
    void clearAllowance(@PathVariable("staffId") UUID staffId);

    @PostMapping("/api/v1/internal/coupons")
    CouponDto issue(@RequestBody Map<String, Object> request,
                    @RequestParam("staffId") UUID staffId,
                    @RequestParam("staffEmail") String staffEmail,
                    @RequestParam("staffRole") String staffRole);

    @GetMapping("/api/v1/internal/coupons")
    List<CouponDto> list(@RequestParam(value = "status", required = false) String status);

    @PutMapping("/api/v1/internal/coupons/{couponId}/status")
    CouponDto setStatus(@PathVariable("couponId") UUID couponId,
                        @RequestBody Map<String, String> body,
                        @RequestParam("staffId") UUID staffId,
                        @RequestParam("staffEmail") String staffEmail,
                        @RequestParam("staffRole") String staffRole);

    // ── Referral programme. Session 64. ─────────────────────────────────────────────────────────
    /*
     * What the platform pays for a referral, and whether each side is switched on.
     *
     * Changing this applies to referrals made AFTERWARDS only — amounts are frozen onto the
     * referral row at attribution, so a rate cut cannot retroactively shrink a promise already made
     * to somebody who has already told their friend about us. Stated here as well as in
     * bmp-rewards because this is where the console reads it, and it is the property a reader will
     * most naturally assume the opposite of.
     */
    record ReferralProgramView(
            java.util.UUID id,
            long referrerRewardPaise, long refereeRewardPaise,
            boolean referrerEnabled, boolean refereeEnabled,
            long referrerPayoutPaise, long refereePayoutPaise,
            boolean effectivelyOff,
            java.time.Instant effectiveFrom, String changedByEmail, String note,
            java.time.Instant createdAt) {}

    record PublishReferralProgram(
            long referrerRewardPaise, long refereeRewardPaise,
            boolean referrerEnabled, boolean refereeEnabled,
            java.time.Instant effectiveFrom,
            java.util.UUID staffId, String staffEmail, String note) {}

    @GetMapping("/api/v1/internal/referral-program/current")
    ReferralProgramView currentReferralProgram();

    @GetMapping("/api/v1/internal/referral-program/history")
    java.util.List<ReferralProgramView> referralProgramHistory();

    @PostMapping("/api/v1/internal/referral-program")
    ReferralProgramView publishReferralProgram(@RequestBody PublishReferralProgram req);
}
