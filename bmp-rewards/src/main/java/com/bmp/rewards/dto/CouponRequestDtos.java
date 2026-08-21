package com.bmp.rewards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTOs for the coupon request/approval workflow (V004). */
public final class CouponRequestDtos {
    private CouponRequestDtos() {}

    /**
     * Who is asking. Assembled by the caller from an authenticated identity — never from the
     * request body, or anyone could raise a request as somebody else and inherit their limits.
     */
    public record RequesterContext(
        String requesterType,   // staff | salon_owner
        UUID requesterId,
        String name,
        String email,
        String role,
        UUID salonId
    ) {}

    /**
     * @param justification why this is needed. <b>Minimum 20 characters, enforced in the
     *        service.</b> A required field that accepts "." is required in name only, and the
     *        approver is being asked to make a decision about money — "needed" tells them
     *        nothing. Twenty characters is roughly one honest sentence.
     */
    public record RaiseRequest(
        @Size(max = 120) String proposedName,

        @NotBlank @Size(min = 20, max = 2000) String justification,
        /** The complaint this settles, for support-raised goodwill. */
        UUID ticketId,

        @NotNull @Pattern(regexp = "^(all_users|selected_users|new_users|referred_users)$")
        String audienceType,
        @Pattern(regexp = "^(all_salons|selected_salons)$") String salonScope,

        @NotNull @Pattern(regexp = "^(flat|percent)$") String discountType,
        /** Paise if flat, basis points if percent. */
        @NotNull @Positive Long value,
        /** Required for percent — a percentage with no ceiling has no upper bound on cost. */
        Long maxDiscountPaise,
        Long minSpendPaise,
        Integer perUserLimit,
        Integer totalUsageCap,

        @NotNull Instant activeFrom,
        @NotNull Instant activeTo,

        /** Required when audienceType = selected_users. */
        List<UUID> targetUserIds,
        /** Required when salonScope = selected_salons. */
        List<UUID> targetSalonIds,

        /**
         * True if the SALON is offering to fund this rather than BMP.
         *
         * <p>A request, not an instruction. The approving admin decides — which is the whole
         * reason a salon owner cannot create coupons directly.
         */
        Boolean salonFunded
    ) {}

    /**
     * @param value            null = grant exactly what was asked. Set it to grant less (or more).
     * @param maxDiscountPaise null = as asked
     * @param activeTo         null = as asked. Shortening a validity window is the commonest
     *                         modification: "yes, but it expires in a week, not a year".
     * @param note             shown to the requester. Required when rejecting — a refusal with
     *                         no reason teaches the requester nothing and they ask again.
     */
    public record DecisionRequest(
        Long value,
        Long maxDiscountPaise,
        Instant activeTo,
        @Size(max = 2000) String note
    ) {}

    public record CouponRequestResponse(
        UUID id, String requestRef, String status,
        String requesterType, UUID requesterId, String requesterName, String requesterEmail,
        String requesterRole, UUID salonId,
        String justification, UUID issuedForTicketId,
        String proposedName, String audienceType, String salonScope,
        String discountType, long value, Long maxDiscountPaise,
        long minSpendPaise, int perUserLimit, Integer totalUsageCap,
        Instant activeFrom, Instant activeTo, String commissionBase,
        int recipientCount,
        /** What was actually granted, when it differs from the ask. Null = as requested. */
        Long approvedValue, Long approvedMaxDiscountPaise, Instant approvedActiveTo,
        UUID decidedByStaffId, String decidedByEmail, Instant decidedAt, String decisionNote,
        UUID createdCouponId,
        /** The code, once approved — what the requester actually needs to hand over. */
        String createdCouponCode,
        Instant createdAt, Instant updatedAt
    ) {}

    /** What a support agent has left this period. Rendered BEFORE the form, not after a refusal. */
    public record AllowanceResponse(
        int periodDays,
        int maxCount, int usedCount, int remainingCount,
        long maxPaise, long usedPaise, long remainingPaise,
        boolean exhausted,
        boolean hasOverride, String overrideReason
    ) {}

    /** Admin editing one agent's allowance. Null fields fall back to the platform default. */
    public record AllowanceOverrideRequest(
        @NotNull UUID staffId,
        String staffEmail,
        Integer maxCountPerPeriod,
        Long maxPaisePerPeriod,
        Long maxFlatPaise,
        Integer maxPercentBps,
        @NotBlank @Size(min = 10, max = 1000) String reason,
        /** Strongly recommended. A temporary raise with no expiry becomes permanent by default. */
        Instant expiresAt
    ) {}

    public record AllowanceOverrideResponse(
        UUID id, UUID staffId, String staffEmail,
        Integer maxCountPerPeriod, Long maxPaisePerPeriod,
        Long maxFlatPaise, Integer maxPercentBps,
        String reason, Instant expiresAt,
        UUID updatedByStaffId, String updatedByEmail,
        Instant createdAt, Instant updatedAt
    ) {}
}
