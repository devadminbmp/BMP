/**
 * Payment MODULE — Razorpay Route, webhook source-of-truth, payouts, commission.
 *
 * <p>Public surface: com.bmp.payment.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: payment_schema — payment, payout_record, payout_queue, bank_account.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Payment",
    allowedDependencies = { "booking :: api", "common" }
)
package com.bmp.payment;
