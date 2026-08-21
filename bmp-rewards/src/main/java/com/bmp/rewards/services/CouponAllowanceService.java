package com.bmp.rewards.services;

import com.bmp.rewards.entities.Coupon;
import com.bmp.rewards.entities.CouponAllowanceOverride;
import com.bmp.rewards.repositories.CouponAllowanceOverrideRepository;
import com.bmp.rewards.repositories.CouponPolicyRepository;
import com.bmp.rewards.repositories.CouponRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How much a support agent has left to give away this week.
 *
 * <h2>Why per-coupon limits were not enough</h2>
 * {@link CouponIssuePolicy} caps a SINGLE coupon: ₹500, 20%, one recipient, thirty days. It says
 * nothing about how many of those an agent may issue. One person could have issued a hundred
 * ₹500 coupons in an afternoon — every one within policy — and the first anyone would know is
 * the month's numbers.
 *
 * <p>This is the budget. It is measured over a ROLLING window rather than a calendar month,
 * because a calendar reset creates a use-it-or-lose-it incentive at month-end, which is the last
 * thing you want attached to an apology budget.
 *
 * <h2>Exceeding it is not a wall</h2>
 * That is the important part, and the difference between this and V003's limits. Going over the
 * allowance does not end the conversation — it routes to {@link CouponRequestService}, where an
 * admin can approve. A refusal with no path forward doesn't enforce a policy; it makes people
 * route around it, usually by borrowing someone's login.
 *
 * <h2>Two judgement calls worth knowing about</h2>
 *
 * <p><b>Percentage coupons count at their cap, not their expected value.</b> A "20%, max ₹400"
 * coupon consumes ₹400 of the allowance even though most redemptions will be worth less. The
 * allowance is a measure of EXPOSURE, and the honest worst case is the cap. Counting the average
 * would let someone issue far more real liability than the budget claims.
 *
 * <p><b>Revoked coupons still count.</b> Issuing ten coupons and revoking nine does not restore
 * the allowance for that period. Otherwise the limit is trivially bypassed by churn, and the
 * number stops measuring anything. The allowance is about how much authority someone exercises
 * in a week, not about the net outcome.
 */
@Service
public class CouponAllowanceService {

    /** Fallbacks if the policy row is missing. Same values as V004 seeds. */
    private static final int DEFAULT_PERIOD_DAYS = 7;
    private static final int DEFAULT_MAX_COUNT = 10;
    private static final long DEFAULT_MAX_PAISE = 300_000L;

    private final CouponRepository coupons;
    private final CouponPolicyRepository policyRepo;
    private final CouponAllowanceOverrideRepository overrides;

    public CouponAllowanceService(CouponRepository coupons, CouponPolicyRepository policyRepo,
                                   CouponAllowanceOverrideRepository overrides) {
        this.coupons = coupons;
        this.policyRepo = policyRepo;
        this.overrides = overrides;
    }

    /**
     * What an agent has, has used, and has left.
     *
     * <p>Rendered by the console BEFORE the form is filled in — an agent who discovers their
     * limit by being refused has already promised something to a customer.
     *
     * @param periodDays     the rolling window
     * @param maxCount       coupons allowed in the window
     * @param usedCount      coupons issued in the window
     * @param maxPaise       total face value allowed in the window
     * @param usedPaise      total face value issued (percent coupons at their cap)
     * @param hasOverride    true if this person has a bespoke allowance rather than the default
     * @param overrideReason why, if so — shown so nobody has to ask
     */
    public record Allowance(
        int periodDays,
        int maxCount, int usedCount, int remainingCount,
        long maxPaise, long usedPaise, long remainingPaise,
        boolean hasOverride, String overrideReason
    ) {
        public boolean exhausted() {
            return remainingCount <= 0 || remainingPaise <= 0;
        }
    }

    public Allowance allowanceFor(UUID staffId) {
        int periodDays = (int) policyLong("support_allowance_period_days", DEFAULT_PERIOD_DAYS);
        CouponAllowanceOverride ovr = activeOverride(staffId);

        int maxCount = ovr != null && ovr.getMaxCountPerPeriod() != null
                ? ovr.getMaxCountPerPeriod()
                : (int) policyLong("support_allowance_max_count", DEFAULT_MAX_COUNT);
        long maxPaise = ovr != null && ovr.getMaxPaisePerPeriod() != null
                ? ovr.getMaxPaisePerPeriod()
                : policyLong("support_allowance_max_paise", DEFAULT_MAX_PAISE);

        Instant since = Instant.now().minus(Duration.ofDays(periodDays));
        List<Coupon> issued = coupons.findByCreatedByStaffIdAndCreatedAtAfter(staffId, since);

        long usedPaise = issued.stream().mapToLong(CouponAllowanceService::exposureOf).sum();
        int usedCount = issued.size();

        return new Allowance(
                periodDays,
                maxCount, usedCount, Math.max(0, maxCount - usedCount),
                maxPaise, usedPaise, Math.max(0, maxPaise - usedPaise),
                ovr != null, ovr != null ? ovr.getReason() : null);
    }

    /**
     * Would issuing this coupon fit inside what's left? Null if yes; the reason if not.
     *
     * <p>Returns a message rather than throwing, because the caller's next step is to OFFER THE
     * REQUEST FLOW, not to fail. Throwing here would make "you're over budget" look identical to
     * "you're not allowed", and those need completely different UI.
     */
    public String wouldExceed(UUID staffId, String discountType, long value, Long maxDiscountPaise) {
        Allowance a = allowanceFor(staffId);
        long exposure = exposureOf(discountType, value, maxDiscountPaise);

        if (a.remainingCount() <= 0) {
            return "You've issued %d coupons in the last %d days, which is your limit. Raise a request for an admin to approve this one."
                    .formatted(a.usedCount(), a.periodDays());
        }
        if (exposure > a.remainingPaise()) {
            return "That would put you over your ₹%d allowance for the last %d days (₹%d left). Raise a request for an admin to approve it."
                    .formatted(a.maxPaise() / 100, a.periodDays(), a.remainingPaise() / 100);
        }
        return null;
    }

    /**
     * What one coupon costs against an allowance.
     *
     * <p>Flat: its face value. Percent: its CAP — see the class javadoc on why the worst case is
     * the honest number. A percentage coupon with no cap is rejected long before it reaches
     * here ({@code CouponIssuePolicy.assertMayIssue}), so the fallback is defensive only; it
     * charges the full remaining budget rather than zero, because an uncapped percentage is
     * unbounded exposure and must never look free.
     */
    static long exposureOf(String discountType, long value, Long maxDiscountPaise) {
        if ("percent".equalsIgnoreCase(discountType)) {
            return maxDiscountPaise != null && maxDiscountPaise > 0 ? maxDiscountPaise : Long.MAX_VALUE;
        }
        return value;
    }

    private static long exposureOf(Coupon c) {
        return exposureOf(c.getDiscountType(), c.getValue(), c.getMaxDiscountPaise());
    }

    /** An override that exists and hasn't expired. A lapsed one is the same as none. */
    private CouponAllowanceOverride activeOverride(UUID staffId) {
        return overrides.findByStaffId(staffId)
                .filter(o -> o.getExpiresAt() == null || o.getExpiresAt().isAfter(Instant.now()))
                .orElse(null);
    }

    private long policyLong(String key, long fallback) {
        return policyRepo.findByPolicyKey(key)
                .map(p -> {
                    try {
                        return Long.parseLong(p.getPolicyValue());
                    } catch (NumberFormatException e) {
                        // A corrupted policy row must never become an unlimited allowance.
                        return fallback;
                    }
                })
                .orElse(fallback);
    }
}
