package com.bmp.rewards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-28 DTOs — coupon / wallet / wallet_transaction / referral_code. */
public final class RewardsDtos {
    private RewardsDtos() {}

    public record CreateCouponRequest(
        @NotBlank String code, @NotBlank String type, UUID salonId, @NotBlank String commissionBase,
        @NotBlank String discountType, long value, long minSpendPaise, int perUserLimit,
        int totalUsageCap, Instant activeFrom, Instant activeTo, boolean allowsWalletStacking
    ) {}

    public record CouponResponse(
        UUID id, String code, String type, UUID salonId, String commissionBase, String discountType,
        long value, long minSpendPaise, int perUserLimit, int totalUsageCap,
        Instant activeFrom, Instant activeTo, boolean allowsWalletStacking, Instant createdAt
    ) {}

    /*
     * Session 55 — ValidateCouponRequest / ValidateCouponResponse are GONE along with the second
     * coupon validator they served. Nothing constructs them any more; the quote path uses
     * CouponQuoteRequest / CouponQuoteResponse in CouponAdminDtos.
     *
     * Deleted rather than deprecated on purpose. A leftover request record is an invitation to
     * write a second validator against it, which is precisely how there came to be two.
     */

    public record WalletResponse(UUID userId, long balancePaise, boolean isFrozen) {}

    public record WalletTransactionResponse(UUID id, String type, long amountPaise, long balanceAfterPaise, Instant createdAt) {}

    public record PagedTransactions(List<WalletTransactionResponse> content, int page, int size, long totalElements) {}

    public record ReferralCodeResponse(UUID userId, String code, Instant createdAt) {}

    public record ErrorResponse(String error, String message) {}
}
