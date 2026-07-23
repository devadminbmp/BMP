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

    public record ValidateCouponRequest(@NotBlank String code, @NotNull UUID userId, UUID salonId, @NotNull long subtotalPaise) {}

    public record ValidateCouponResponse(boolean valid, UUID couponId, Long discountPaise, String commissionBase, String reason) {}

    public record WalletResponse(UUID userId, long balancePaise, boolean isFrozen) {}

    public record WalletTransactionResponse(UUID id, String type, long amountPaise, long balanceAfterPaise, Instant createdAt) {}

    public record PagedTransactions(List<WalletTransactionResponse> content, int page, int size, long totalElements) {}

    public record ReferralCodeResponse(UUID userId, String code, Instant createdAt) {}

    public record ErrorResponse(String error, String message) {}
}
