-- V005 — record the coupon applied to a booking.
--
-- ============================================================================================
-- WHY THE BOOKING STORES THIS, WHEN rewards_schema.coupon_usage ALREADY EXISTS
-- ============================================================================================
-- coupon_usage answers "how many times has this coupon been used" — it belongs to the coupon.
-- These columns answer "what did this customer actually agree to pay, and why" — they belong to
-- the booking, and they must survive independently.
--
-- Concretely: if a coupon is later paused, edited, or its percentage changed, a booking made
-- under the old terms must still show the old numbers. A booking that recalculates its own
-- price from a mutable coupon row is a booking whose total silently changes after the customer
-- agreed to it. That is the single most common way a marketplace ends up in a dispute it cannot
-- win, because it has no record of what it originally promised.
--
-- Same principle as policy_snapshot on this table (V002): freeze the terms, don't re-derive them.
-- ============================================================================================

-- Which coupon, if any. Logical ref -> rewards_schema.coupon.id; cross-schema FKs are not used
-- in this repo, and here it matters more than usual: a booking must remain readable even if a
-- coupon is one day purged.
ALTER TABLE booking_schema.booking ADD COLUMN IF NOT EXISTS coupon_id UUID;

-- What it took off, in integer paise. NOT NULL DEFAULT 0 so every existing booking reads as
-- "no discount" without a backfill, which is exactly what they were.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS discount_paise BIGINT NOT NULL DEFAULT 0;

-- The price BEFORE the discount.
--
-- Kept explicitly rather than computed as final + discount, because final_amount_paise will
-- later be touched by refunds and adjustments, and at that point the arithmetic stops working.
-- Storing what the basket was worth at checkout keeps the original agreement legible forever.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS gross_amount_paise BIGINT NOT NULL DEFAULT 0;

-- pre_discount = the salon absorbs the discount (commission on the full price)
-- post_discount = BMP absorbs it (commission on what was actually paid)
--
-- Snapshotted at checkout. Recalculating this later — after the coupon has been paused or
-- edited — would produce a different answer than the one the salon agreed to, and settlement
-- disputes are exactly where "we recalculated it" is not an acceptable sentence.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS commission_base VARCHAR(15) NOT NULL DEFAULT 'post_discount';

-- Existing rows: gross equals final, because none of them had a discount.
UPDATE booking_schema.booking
SET gross_amount_paise = final_amount_paise
WHERE gross_amount_paise = 0;

-- "Which bookings used this coupon" is asked by finance and by support investigating a dispute.
CREATE INDEX IF NOT EXISTS idx_booking_coupon ON booking_schema.booking(coupon_id)
    WHERE coupon_id IS NOT NULL;
