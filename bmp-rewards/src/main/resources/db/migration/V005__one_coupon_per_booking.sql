-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V005 — one coupon per booking. Session 55.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE RULE ───────────────────────────────────────────────────────────────────────────────────
-- Darshan: *"we need to have rules also — like a customer uses one coupon, he can't apply another
-- one, like that as standard."* Standard is right: every major consumer app in India works this
-- way. One code per order, and applying a new one replaces the old rather than adding to it.
--
-- ── WHY IT NEEDED A MIGRATION AND NOT JUST AN `if` ─────────────────────────────────────────────
-- The rule LOOKED enforced and was not. Two things gave that impression:
--
--   1. `CreateBookingRequest` carries a single `couponCode`, so the ordinary path can only ever
--      send one. That is a property of today's DTO, not a guarantee — the moment somebody adds a
--      "change coupon" or "apply another" endpoint, it evaporates.
--
--   2. `CouponRedemptionService.redeem` already had a duplicate check, and it reads as if it
--      covers this. It does not: it looks up `(coupon_id, booking_id)`, which makes REDEEMING THE
--      SAME COUPON TWICE idempotent — the right behaviour for a Feign retry — while saying nothing
--      at all about a DIFFERENT coupon on the same booking. Two codes, two usage rows, two
--      discounts, and the booking's final amount reduced twice.
--
-- This is the same shape as the payment-uniqueness problem in Session 50: a comment and a partial
-- check that together read like a guarantee nobody had actually written. So the guarantee goes
-- where it cannot be bypassed — the database.
--
-- ── WHAT ABOUT THE WALLET? ─────────────────────────────────────────────────────────────────────
-- `coupon.allows_wallet_stacking` exists and, as of this migration, still has nothing to stack
-- with: there is no wallet-spend path anywhere in the product. Wallet credit is a PAYMENT METHOD,
-- not a second coupon, and it belongs with the payment work. Deliberately NOT modelled here —
-- inventing a rule for a feature that does not exist is how a column comes to claim a guarantee
-- nothing enforces, which is the exact mistake this migration is correcting.
--
-- ── BACKFILL ───────────────────────────────────────────────────────────────────────────────────
-- A partial unique index cannot be added if the data already violates it. Any booking that
-- somehow acquired two coupons keeps the EARLIEST usage (the one the customer was actually shown
-- a price for) and the later rows are deleted. Logged by the DO block so it is not silent.

DO $$
DECLARE
    dupes INT;
BEGIN
    SELECT COUNT(*) INTO dupes FROM (
        SELECT booking_id FROM rewards_schema.coupon_usage
        GROUP BY booking_id HAVING COUNT(*) > 1
    ) d;

    IF dupes > 0 THEN
        RAISE WARNING 'V005: % booking(s) had more than one coupon redeemed. Keeping the earliest '
                      'usage per booking and removing the rest — the customer agreed to the price '
                      'shown at the first redemption.', dupes;

        DELETE FROM rewards_schema.coupon_usage cu
        WHERE cu.id NOT IN (
            SELECT DISTINCT ON (booking_id) id
            FROM rewards_schema.coupon_usage
            ORDER BY booking_id, created_at ASC, id ASC
        );
    END IF;
END $$;

-- THE guarantee. One coupon, one booking, enforced by Postgres rather than by remembering.
CREATE UNIQUE INDEX IF NOT EXISTS uq_coupon_usage_one_per_booking
    ON rewards_schema.coupon_usage (booking_id);

COMMENT ON INDEX rewards_schema.uq_coupon_usage_one_per_booking IS
    'One coupon per booking (Session 55). The service check in CouponRedemptionService is keyed on '
    '(coupon_id, booking_id) and makes a RETRY of the same coupon idempotent — it does not stop a '
    'DIFFERENT coupon being added to the same booking. This index does. See V005.';
