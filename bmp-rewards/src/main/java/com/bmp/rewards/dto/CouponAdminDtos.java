package com.bmp.rewards.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Staff-facing coupon shapes (V003).
 *
 * <p>Called by bmp-admin over the internal service credential — never by a customer, and never
 * by the console directly.
 */
public final class CouponAdminDtos {
    private CouponAdminDtos() {}

    /**
     * Who is issuing this.
     *
     * <p>Passed in rather than read from a token: bmp-rewards has no notion of staff sessions
     * and shouldn't gain one. bmp-admin has already authenticated them; this carries the facts
     * needed to enforce the policy and to record provenance on the row.
     */
    public record StaffContext(UUID staffId, String email, String role) {}

    public record CreateCouponRequest(
        /** Optional — generated if blank, in a form that survives being read aloud. */
        @Size(max = 40) String code,
        @Size(max = 120) String name,
        String description,

        /** welcome/referral/loyalty/off_peak/festival/birthday/win_back/salon_specific (V002). */
        @NotNull String couponType,

        /** all_users | selected_users | new_users | referred_users */
        @NotNull @Pattern(regexp = "^(all_users|selected_users|new_users|referred_users)$")
        String audienceType,

        /** all_salons | selected_salons */
        @NotNull @Pattern(regexp = "^(all_salons|selected_salons)$")
        String salonScope,

        @NotNull @Pattern(regexp = "^(flat|percent)$") String discountType,
        /** Paise if flat, basis points if percent — matching V002's `value` column. */
        @NotNull @Positive Long value,
        /** Required for percent coupons; the service refuses without it. */
        Long maxDiscountPaise,
        Long minSpendPaise,
        Integer perUserLimit,
        Integer totalUsageCap,

        @NotNull Instant activeFrom,
        @NotNull Instant activeTo,
        Boolean allowsWalletStacking,

        /** Required when audienceType = selected_users. */
        List<UUID> targetUserIds,
        /** Required when salonScope = selected_salons. */
        List<UUID> targetSalonIds,

        /**
         * True if the SALON bears the cost rather than BMP.
         *
         * <p>Only meaningful for salon-scoped coupons, and only honoured there — the service
         * derives `commission_base` from this rather than trusting a raw value, so nobody can
         * accidentally bill a salon for BMP's apology.
         */
        Boolean salonFunded,

        /** Required for support-issued coupons: the complaint this settles. */
        UUID ticketId,
        String issueReason
    ) {}

    public record CouponResponse(
        UUID id, String code, String name, String description, String couponType,
        String audienceType, String salonScope, String status,
        String discountType, long value, Long maxDiscountPaise, long minSpendPaise,
        int perUserLimit, int totalUsageCap,
        Instant activeFrom, Instant activeTo,
        /** pre_discount = the salon funds it; post_discount = BMP funds it. */
        String commissionBase,
        List<UUID> targetUserIds, List<UUID> targetSalonIds,
        String createdByEmail, String createdByRole, UUID issuedForTicketId, String issueReason,
        long timesUsed,
        Instant createdAt
    ) {}

    public record SetCouponStatusRequest(@NotNull @Pattern(regexp = "^(active|paused|revoked)$") String status) {}

    /** What a role may issue — the console renders its form from this rather than guessing. */
    public record CouponLimitsResponse(
        boolean unrestricted,
        long maxFlatPaise,
        int maxPercentBasisPoints,
        int maxValidityDays,
        int maxRecipients,
        boolean requiresTicket,
        List<String> allowedAudienceTypes
    ) {}
}
