package com.bmp.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Opening a payment order for a booking. Session 50.
 *
 * <h2>Why bmp-booking calls bmp-payment and not the other way round</h2>
 * The booking is the thing the customer is doing; the payment is a step inside it. Booking knows
 * the amount, the salon and the salon's commission rate — it has already loaded the policy to
 * freeze the cancellation snapshot — so it can open the order in one call with no lookups.
 *
 * <p>The reverse would mean bmp-payment reaching into bmp-booking for an amount, which is both a
 * cross-service read on the hot path and an invitation to disagree about the price.
 *
 * <h2>The commission rate is passed, deliberately</h2>
 * bmp-payment could fetch it from bmp-salon itself. It should not: one booking should read the
 * salon's terms ONCE, so the commission split and the cancellation snapshot beside it can never
 * be taken from two different reads of a policy somebody edited in between.
 */
@FeignClient(name = "bmp-payment-service",
        configuration = com.bmp.booking.config.FeignInternalKeyConfig.class)
public interface PaymentServiceClient {

    /**
     * Idempotent server-side: a retried booking returns the existing order rather than failing.
     *
     * @param commissionBps the SALON's rate from salon_policy — never a platform constant. A null
     *                      here makes bmp-payment fall back to 12% and log a fault, because a
     *                      missing rate means a negotiated deal was not applied to real money.
     */
    @PostMapping("/api/v1/payment-orders/booking/{bookingId}")
    PaymentOrderView create(@PathVariable("bookingId") UUID bookingId,
                             @RequestBody CreateOrder body);

    record CreateOrder(long amountPaise, UUID salonId, Integer commissionBps, String receipt) {}

    /** Only the fields bmp-booking acts on. The payment service owns the rest. */
    record PaymentOrderView(UUID id, UUID bookingId, long amountPaise, long commissionPaise,
                             long salonSharePaise, String razorpayOrderId, String status) {}
}
