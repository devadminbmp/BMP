/**
 * Rewards MODULE — coupons, non-withdrawable wallet, referrals, loyalty (Phase 2).
 *
 * <p>Public surface: com.bmp.rewards.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: rewards_schema — coupon, coupon_usage, wallet, wallet_transaction,
 * referral, referral_code, checkout_discount, win_back_job_log, loyalty_account,
 * loyalty_transaction. loyalty_* tables are schema-ready but the feature is OFF in Phase 1.
 *
 * <p>UPDATED: this comment previously listed a simpler 5-table version. CONTEXT.md's
 * later, more detailed Session-3 design specifies the 10 tables above instead —
 * corrected to match. See V007b__rewards_schema.sql.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.rewards;
