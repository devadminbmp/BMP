package com.bmp.rewards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/**
 * Checking and applying a coupon at checkout.
 *
 * <p><b>Note what the client never sends: a discount amount.</b> It sends a code and a basket;
 * the server decides what it's worth. Accepting a discount figure from a request body is how a
 * marketplace gives money away.
 */
public final class CouponRedemptionDtos {
    private CouponRedemptionDtos() {}

    /**
     * @param basketPaise    the total BEFORE discount, in integer paise
     * @param isFirstBooking whether this is the customer's first ever booking — bmp-booking owns
     *                       that fact, so it tells us rather than us duplicating the query
     */
    public record CouponQuoteRequest(
        @NotBlank String code,
        @NotNull UUID userId,
        @NotNull UUID salonId,
        @PositiveOrZero long basketPaise,
        Boolean isFirstBooking
    ) {}

    /**
     * The answer, whether yes or no.
     *
     * <p>A refusal carries a SENTENCE, not a code. "This coupon isn't valid" produces a support
     * ticket; "this code needs a minimum spend of ₹1,000" doesn't, because the customer can act
     * on it.
     */
    public record CouponQuoteResponse(
        boolean valid,
        UUID couponId,
        String code,
        String name,
        long discountPaise,
        long totalAfterDiscountPaise,
        String reason
    ) {
        public static CouponQuoteResponse refused(String reason) {
            return new CouponQuoteResponse(false, null, null, null, 0, 0, reason);
        }

        public static CouponQuoteResponse accepted(UUID id, String code, String name,
                                                    long discount, long total) {
            return new CouponQuoteResponse(true, id, code, name, discount, total, null);
        }
    }

    /** Called by bmp-booking inside the booking transaction, with the booking it belongs to. */
    public record CouponRedeemRequest(
        @NotBlank String code,
        @NotNull UUID userId,
        @NotNull UUID salonId,
        @NotNull UUID bookingId,
        @PositiveOrZero long basketPaise,
        Boolean isFirstBooking
    ) {}

    /**
     * @param commissionBase pre_discount = the salon absorbs it; post_discount = BMP does.
     *                       bmp-booking needs this to snapshot the right commission basis onto
     *                       the booking — recalculating it later, after a coupon has been
     *                       paused or edited, would produce a different answer than the one the
     *                       salon agreed to.
     */
    public record CouponRedeemResponse(
        UUID couponId,
        String code,
        long discountPaise,
        String commissionBase
    ) {}
}
