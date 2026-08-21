package com.bmp.rewards.services;

import com.bmp.common.money.Money;
import com.bmp.rewards.dto.CouponRedemptionDtos.*;
import com.bmp.rewards.entities.Coupon;
import com.bmp.rewards.entities.CouponUsage;
import com.bmp.rewards.repositories.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Deciding whether a coupon applies, and by how much.
 *
 * <p>This is the half of coupons that was missing: Session 20 built issuing, but nothing
 * validated or applied a code at checkout — so every coupon in the system was decorative.
 *
 * <h2>Two entry points, one set of rules</h2>
 * <ul>
 *   <li>{@link #quote} — customer-facing, called while they're typing a code. Read-only, and
 *       returns a REASON when it refuses.</li>
 *   <li>{@link #redeem} — internal, called by bmp-booking inside the booking transaction.
 *       Re-runs every check and records the usage.</li>
 * </ul>
 * They share {@link #evaluate} deliberately. If quote and redeem could ever disagree, a
 * customer would see "₹300 off" and then be charged full price — and the checks would drift
 * apart the first time one was edited without the other.
 *
 * <h2>The discount is computed here, never sent by the client</h2>
 * The client says "here is a code and a basket"; the server says what it's worth. Accepting a
 * discount amount from a request body is how a marketplace gives away money.
 *
 * <h2>Refusals are specific</h2>
 * "This coupon isn't valid" produces a support ticket. "This coupon needs a minimum spend of
 * ₹1,000" doesn't — the customer adds a service or picks another code. Every rejection path
 * below names the actual reason.
 */
@Service
public class CouponRedemptionService {

    private static final Logger log = LoggerFactory.getLogger(CouponRedemptionService.class);

    private final CouponRepository coupons;
    private final CouponUsageRepository usage;
    private final CouponUserRepository couponUsers;
    private final CouponSalonRepository couponSalons;
    private final ReferralRepository referrals;

    public CouponRedemptionService(CouponRepository coupons, CouponUsageRepository usage,
                                   CouponUserRepository couponUsers, CouponSalonRepository couponSalons,
                                   ReferralRepository referrals) {
        this.coupons = coupons;
        this.usage = usage;
        this.couponUsers = couponUsers;
        this.couponSalons = couponSalons;
        this.referrals = referrals;
    }

    /** Read-only check, for the checkout screen. Never writes, never throws on a refusal. */
    public CouponQuoteResponse quote(CouponQuoteRequest req) {
        Coupon coupon = coupons.findByCode(req.code().trim().toUpperCase()).orElse(null);
        if (coupon == null) {
            return CouponQuoteResponse.refused("We don't recognise that code. Check the spelling?");
        }

        Refusal refusal = evaluate(coupon, req.userId(), req.salonId(), req.basketPaise(), req.isFirstBooking());
        if (refusal != null) {
            return CouponQuoteResponse.refused(refusal.message());
        }

        long discount = discountFor(coupon, req.basketPaise());
        return CouponQuoteResponse.accepted(
                coupon.getId(), coupon.getCode(), coupon.getName(),
                discount, Math.max(0, req.basketPaise() - discount));
    }

    /**
     * Apply the coupon and record it. Called by bmp-booking, inside the booking transaction.
     *
     * <p><b>Locks the coupon row.</b> Two customers redeeming the last use of a limited coupon
     * at the same moment would both pass a count-then-insert check and both get the discount.
     * A pessimistic lock costs nothing at this volume (one row, held for milliseconds) and
     * removes the whole class of bug. The alternative — accepting occasional overshoot — is a
     * defensible choice for a marketing coupon and an indefensible one for a ₹5,000 launch
     * offer, so we don't make it conditional on the coupon.
     *
     * <p>Throws on refusal rather than returning one: by this point the customer has been told
     * the discount applies, so a silent downgrade to full price would be worse than a failed
     * booking they can retry.
     */
    @Transactional
    public CouponRedeemResponse redeem(CouponRedeemRequest req) {
        Coupon coupon = coupons.findByCodeForUpdate(req.code().trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND"));

        // A retry of the same booking must not consume a second use. bmp-booking can retry for
        // reasons that have nothing to do with coupons (a Feign timeout, a client resend), and
        // charging the customer's allowance twice for one booking would be our bug, not theirs.
        CouponUsage existing = usage.findByCouponIdAndBookingId(coupon.getId(), req.bookingId()).orElse(null);
        if (existing != null) {
            log.info("Coupon {} already redeemed for booking {} — returning the original discount",
                    coupon.getCode(), req.bookingId());
            return new CouponRedeemResponse(coupon.getId(), coupon.getCode(),
                    existing.getDiscountAppliedPaise().paise(), coupon.getCommissionBase());
        }

        Refusal refusal = evaluate(coupon, req.userId(), req.salonId(), req.basketPaise(), req.isFirstBooking());
        if (refusal != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, refusal.code() + ": " + refusal.message());
        }

        long discount = discountFor(coupon, req.basketPaise());
        usage.save(new CouponUsage(coupon.getId(), req.userId(), req.bookingId(),
                Money.ofPaise(discount), false));

        log.info("Coupon redeemed: code={} user={} booking={} discount={}p",
                coupon.getCode(), req.userId(), req.bookingId(), discount);

        return new CouponRedeemResponse(coupon.getId(), coupon.getCode(), discount, coupon.getCommissionBase());
    }

    /**
     * Give a use back when a booking never happens.
     *
     * <p>Called when a booking is cancelled before it was ever paid for. The customer shouldn't
     * lose a one-per-person coupon because a salon closed unexpectedly.
     *
     * <p>Marked rather than deleted: the row is evidence that the code WAS applied and then
     * released, which matters if anyone later asks why a customer has used a "single use"
     * coupon twice.
     *
     * <p><b>Wired since Session 22:</b> {@code BookingService.cancel} calls this via
     * {@code RewardsServiceClient.release} whenever the cancelled booking carried a coupon. The
     * call is best-effort — a failure here logs loudly and does NOT block the cancellation,
     * because being unable to cancel is a far worse outcome for the customer than a coupon that
     * needs restoring by hand.
     *
     * <p>(This javadoc said "nothing calls this yet" for eight sessions after it stopped being
     * true, and a Session 29 audit duly reported the coupon bug that no longer existed. Stale
     * comments cost real time — if you fix the thing a comment warns about, fix the comment in
     * the same commit.)
     */
    @Transactional
    public void release(UUID bookingId, String reason) {
        usage.findByBookingId(bookingId).forEach(u -> {
            u.markRefunded();
            usage.save(u);
            log.info("Coupon usage released for booking {} ({})", bookingId, reason);
        });
    }

    // ======================================================================================
    // The rules
    // ======================================================================================

    /** A refusal, with a code for logs and a sentence for the customer. */
    private record Refusal(String code, String message) {}

    /**
     * Every check, in the order that produces the most useful message.
     *
     * <p>Ordering matters: "this coupon has expired" is more useful than "you've already used
     * this coupon" when both are true, because the first tells them to stop trying.
     */
    private Refusal evaluate(Coupon coupon, UUID userId, UUID salonId, long basketPaise, Boolean isFirstBooking) {
        if ("paused".equalsIgnoreCase(coupon.getStatus())) {
            return new Refusal("COUPON_PAUSED", "This offer is paused at the moment.");
        }
        if ("revoked".equalsIgnoreCase(coupon.getStatus())) {
            return new Refusal("COUPON_REVOKED", "This code is no longer available.");
        }
        if (!coupon.isRedeemableNow()) {
            return new Refusal("COUPON_NOT_ACTIVE", "This offer has expired or hasn't started yet.");
        }

        // Minimum spend before audience, because it's the one the customer can fix right now.
        long minSpend = coupon.getMinSpendPaise() == null ? 0 : coupon.getMinSpendPaise().paise();
        if (basketPaise < minSpend) {
            return new Refusal("MIN_SPEND_NOT_MET",
                    "This code needs a minimum spend of ₹%d.".formatted(minSpend / 100));
        }

        Refusal audience = checkAudience(coupon, userId, isFirstBooking);
        if (audience != null) return audience;

        if (CouponIssuePolicy.SCOPE_SELECTED_SALONS.equals(coupon.getSalonScope())
                && !couponSalons.existsByCouponIdAndSalonId(coupon.getId(), salonId)) {
            return new Refusal("WRONG_SALON", "This code doesn't apply at this salon.");
        }

        long usedByUser = usage.findByCouponIdAndUserId(coupon.getId(), userId).stream()
                .filter(u -> !u.isWasRefunded())
                .count();
        if (coupon.getPerUserLimit() > 0 && usedByUser >= coupon.getPerUserLimit()) {
            return new Refusal("PER_USER_LIMIT_REACHED",
                    coupon.getPerUserLimit() == 1
                            ? "You've already used this code."
                            : "You've used this code the maximum number of times.");
        }

        // 0 means unlimited — matching V002, where the column is nullable and the entity maps
        // it to a primitive int.
        if (coupon.getTotalUsageCap() > 0 && usage.countByCouponId(coupon.getId()) >= coupon.getTotalUsageCap()) {
            return new Refusal("FULLY_REDEEMED", "This offer has been fully claimed.");
        }

        return null;
    }

    private Refusal checkAudience(Coupon coupon, UUID userId, Boolean isFirstBooking) {
        String audience = coupon.getAudienceType() == null
                ? CouponIssuePolicy.AUDIENCE_ALL_USERS
                : coupon.getAudienceType();

        switch (audience) {
            case CouponIssuePolicy.AUDIENCE_SELECTED_USERS -> {
                // The check that makes a leaked support coupon worthless to a stranger.
                if (!couponUsers.existsByCouponIdAndUserId(coupon.getId(), userId)) {
                    return new Refusal("NOT_FOR_THIS_USER", "This code isn't valid on your account.");
                }
            }
            case CouponIssuePolicy.AUDIENCE_NEW_USERS -> {
                // bmp-booking tells us whether this is their first booking — it owns that fact,
                // and asking it beats duplicating a "has ever booked" query here.
                if (!Boolean.TRUE.equals(isFirstBooking)) {
                    return new Refusal("NEW_USERS_ONLY", "This code is only for a first booking.");
                }
            }
            case CouponIssuePolicy.AUDIENCE_REFERRED_USERS -> {
                boolean wasReferred = referrals.findByRefereeUserId(userId)
                        .filter(r -> r.getFraudReason() == null)
                        .isPresent();
                if (!wasReferred) {
                    return new Refusal("REFERRED_USERS_ONLY",
                            "This code is only for customers who joined through a referral.");
                }
            }
            default -> { /* all_users — no restriction */ }
        }
        return null;
    }

    /**
     * What it's worth.
     *
     * <p>Percentage coupons store basis points (2000 = 20%), so the arithmetic is integer
     * throughout — money never touches a float in this system.
     *
     * <p>Capped three ways: by the coupon's own {@code max_discount_paise}, and by the basket
     * itself. A discount larger than the basket would produce a negative total, which downstream
     * would become a refund we never intended to issue.
     */
    private long discountFor(Coupon coupon, long basketPaise) {
        long raw = "percent".equalsIgnoreCase(coupon.getDiscountType())
                ? (basketPaise * coupon.getValue()) / 10_000L
                : coupon.getValue();

        if (coupon.getMaxDiscountPaise() != null && coupon.getMaxDiscountPaise() > 0) {
            raw = Math.min(raw, coupon.getMaxDiscountPaise());
        }
        return Math.max(0, Math.min(raw, basketPaise));
    }
}
