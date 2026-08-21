-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- V007 — rescheduling, and a cancellation fee that is actually decided.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- TWO THINGS THIS TABLE COULD NOT ANSWER
--
-- 1. "When was this appointment ORIGINALLY for?"
--    The times live on booking_service_item, and rescheduling moves them. Once moved, the
--    first-ever appointment time is gone — and that is the moment the cancellation clock must
--    run against (see below). Without this column, reschedule is a refund loophole.
--
-- 2. "What did this cancellation cost?"
--    `free_cancel_hours` has been frozen into policy_snapshot since Session 30 and read by
--    NOTHING. A customer cancelling two minutes before their appointment and one cancelling
--    three weeks out got identical treatment: no fee, no record, no difference.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- WHY original_start EXISTS: THE LOOPHOLE, CONCRETELY
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--   1. Customer books Saturday 11:00. The salon's free-cancel window is 24 hours.
--   2. Saturday 10:00 — one hour before, deep inside the fee window — they RESCHEDULE to
--      next month.
--   3. If the clock measured against the CURRENT appointment time they would now be weeks
--      clear of the window.
--   4. They cancel. Free.
--
-- The salon lost Saturday's slot with an hour's notice and was paid nothing.
--
-- So the fee is computed against `original_start`, which is written once at creation and NEVER
-- updated by a reschedule. The salon can opt out per-policy
-- (`reschedule_keeps_original_clock`), which is why the column stores the anchor rather than
-- the decision — the decision belongs to the policy that was frozen for this booking.
--
-- NULLABLE, with no backfill. Bookings made before V007 have no anchor; the service falls back
-- to the earliest service_start on the booking, which for a booking that has never been
-- rescheduled IS the original start. Correct for every existing row, by construction — none of
-- them can have been rescheduled, because rescheduling did not exist.
-- ═══════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE booking_schema.booking ADD COLUMN IF NOT EXISTS original_start TIMESTAMPTZ;

-- How many times this booking has been moved. Checked against the salon's
-- max_reschedules_per_booking, which is frozen into policy_snapshot like everything else — a
-- salon tightening its limit must not retroactively strand a customer mid-booking.
--
-- Counts CUSTOMER moves only. A salon moving its own bookings (a stylist quits, the day
-- shifts) must not eat the customer's allowance — the customer didn't ask for that change and
-- shouldn't be punished for it. `booking_modification.actor` records which is which.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS reschedule_count INT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------------------------
-- The cancellation decision
-- ---------------------------------------------------------------------------------------------
--
-- WRITTEN, NOT COMPUTED ON READ. Two reasons, and the second is the one that matters:
--
--   · The fee depends on how far ahead of the appointment the cancellation was, which is a fact
--     about a moment that has passed. Re-deriving it later needs "now" to be a different value
--     each time it runs, and the answer would drift.
--   · It is what the customer was TOLD. The app shows the fee before they confirm; storing the
--     same number means a dispute is settled by reading a row, not by re-running a calculation
--     against a policy that may since have changed. Same principle as policy_snapshot itself.

-- Basis points of final_amount_paise. 0 = cancelled free.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS cancellation_fee_bps INT NOT NULL DEFAULT 0;

-- The rupee figure the customer was shown, in integer paise. Stored as well as the rate because
-- final_amount_paise can later be touched by refunds and adjustments, at which point recomputing
-- bps × amount stops reproducing what anyone actually agreed to.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS cancellation_fee_paise BIGINT NOT NULL DEFAULT 0;

-- Which band applied, as words: free | late | no_notice | salon_cancelled | no_policy.
--
-- Not derivable from the bps — a salon whose late fee is 0 produces the same 0 as a free
-- cancellation, and "you cancelled in good time" reads very differently from "we charge nothing
-- for late cancellations". Support reads this column; it should not have to reverse-engineer
-- intent from a number.
--
-- `salon_cancelled` is always fee-free, whatever the timing. A salon-caused cancellation
-- charging the customer would be indefensible.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS cancellation_fee_reason VARCHAR(20);

ALTER TABLE booking_schema.booking
    ADD CONSTRAINT chk_booking_cancellation_fee
    CHECK (cancellation_fee_bps BETWEEN 0 AND 10000 AND cancellation_fee_paise >= 0);

-- ---------------------------------------------------------------------------------------------
-- booking_modification gets the two columns it always needed
-- ---------------------------------------------------------------------------------------------
--
-- The table has existed since V002 and NOT ONE ROW has ever been written to it — rescheduling
-- was described in BookingStatus's javadoc and never built. (That javadoc was also wrong: it
-- says reschedule "mutates scheduled_start/end on a CONFIRMED booking", and those columns do
-- not exist on this table. The times are on booking_service_item.)
--
-- before_snapshot/after_snapshot alone cannot answer "who moved this, and why?", which is the
-- first question asked when a customer complains their appointment changed.
ALTER TABLE booking_schema.booking_modification
    ADD COLUMN IF NOT EXISTS actor VARCHAR(20) NOT NULL DEFAULT 'customer';

ALTER TABLE booking_schema.booking_modification
    ADD COLUMN IF NOT EXISTS actor_id UUID;

ALTER TABLE booking_schema.booking_modification
    ADD COLUMN IF NOT EXISTS reason TEXT;

CREATE INDEX IF NOT EXISTS idx_booking_modification_booking
    ON booking_schema.booking_modification(booking_id, created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- "What's coming up?" — the salon's most-asked question, and until now an unindexed one.
--
-- The salon history query orders by created_at, so a booking made yesterday for next month
-- sorts above one made last week for tomorrow. Useless for an upcoming view, which has to order
-- by when the APPOINTMENT is. That lives on booking_service_item, so the index goes there.
-- ---------------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_booking_item_start
    ON booking_schema.booking_service_item(service_start);
