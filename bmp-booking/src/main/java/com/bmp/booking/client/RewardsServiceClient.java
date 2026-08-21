package com.bmp.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Applies a coupon to a booking, and gives the use back if the booking is cancelled.
 *
 * <p>bmp-booking never decides what a coupon is worth — it sends the code and the basket, and
 * bmp-rewards answers. Duplicating the discount rules here would mean two implementations that
 * eventually disagree, and the one that quietly wins is whichever runs last.
 */
@FeignClient(name = "bmp-rewards-service", configuration = com.bmp.booking.config.FeignInternalKeyConfig.class)
public interface RewardsServiceClient {

    /**
     * @param basketPaise    total BEFORE discount
     * @param isFirstBooking whether this is the customer's first ever booking — bmp-booking owns
     *                       that fact, so it tells bmp-rewards rather than the other way round
     */
    record RedeemRequest(
        String code, UUID userId, UUID salonId, UUID bookingId,
        long basketPaise, Boolean isFirstBooking
    ) {}

    /**
     * @param commissionBase pre_discount = the salon absorbs the discount; post_discount = BMP
     *                       does. Snapshotted onto the booking, never recalculated.
     */
    record RedeemResponse(UUID couponId, String code, long discountPaise, String commissionBase) {}

    @PostMapping("/api/v1/coupons/internal/redeem")
    RedeemResponse redeem(@RequestBody RedeemRequest request);

    /**
     * Give the coupon use back when a booking is cancelled before it was ever paid for.
     *
     * <p>The customer shouldn't lose a one-per-person coupon because a salon closed
     * unexpectedly.
     */
    @PostMapping("/api/v1/coupons/internal/release/{bookingId}")
    void release(@PathVariable("bookingId") UUID bookingId, @RequestParam("reason") String reason);
}
