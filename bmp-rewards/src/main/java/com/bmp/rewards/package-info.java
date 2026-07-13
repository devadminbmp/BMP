/**
 * Rewards MODULE — coupons, non-withdrawable wallet, referrals.
 *
 * <p>Public surface: com.bmp.rewards.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: rewards_schema — coupon, coupon_usage, wallet, wallet_transaction, referral.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Rewards",
    allowedDependencies = { "common" }
)
package com.bmp.rewards;
