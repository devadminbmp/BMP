-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- V010 — every salon sets its own cancellation and reschedule terms.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- WHAT V002 GAVE US, AND WHAT WAS MISSING
-- `free_cancel_hours` has existed since V002 and — until Session 37 — was never read by
-- anything. Session 30 froze it into `booking.policy_snapshot`, which was the right half of the
-- job: the terms a customer agreed to are now recorded. Nothing then USED them. A customer
-- cancelling two minutes before their appointment and one cancelling three weeks out got
-- exactly the same outcome: nothing.
--
-- And there was no notion of rescheduling at all. `booking_modification` has sat in
-- booking_schema since V002 with not one row ever written to it.
--
-- WHY TIERS RATHER THAN ONE CUT-OFF
-- A single `free_cancel_hours` boundary forces every salon into "free, or the customer pays
-- everything". Real salons don't work that way: a day's notice usually costs something but not
-- the full price, because the slot can often be half-filled. Two boundaries and two rates cover
-- what salons actually do, and a salon that wants the simple version sets both rates equal.
--
--   hours_until_appointment >= free_cancel_hours          → free
--   late_cancel_hours <= hours_until < free_cancel_hours   → late_cancel_fee_bps
--   hours_until < late_cancel_hours                        → no_notice_fee_bps
--
-- Basis points, not percentages, and never floats — the same rule as commission_bps. 5000 =
-- 50.00%. A percentage stored as a float is a rounding argument waiting to happen, on money.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- THE ONE THAT MATTERS MOST: reschedule_keeps_original_clock
-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- Without it, reschedule is a refund loophole and it is not a subtle one:
--
--   1. Customer books Saturday 11:00. Salon's free-cancel window is 24 hours.
--   2. Saturday 10:00 — an hour before, deep inside the fee window — they RESCHEDULE to next
--      month.
--   3. The clock now measures against next month, so they are comfortably outside the window.
--   4. They cancel. Free.
--
-- The salon lost Saturday's slot with an hour's notice and was paid nothing. Every serious
-- booking platform ties these together, and it is always the salon's money that pays for
-- getting it wrong.
--
-- So the fee clock runs against `booking.original_start` — the FIRST appointment ever booked —
-- not against wherever the booking has since been moved to. The column here is a per-salon
-- escape hatch defaulting to TRUE (closed); a salon that genuinely wants to be more generous
-- can open it, deliberately, knowing what it means.
-- ═══════════════════════════════════════════════════════════════════════════════════════════

-- ---------------------------------------------------------------------------------------------
-- 1. Cancellation fee tiers
-- ---------------------------------------------------------------------------------------------

-- The inner boundary. Below this, the salon has essentially no chance of refilling the slot.
-- Default 2 hours with a 100% fee is the standard "no notice" band; a salon wanting a single
-- cut-off sets late_cancel_fee_bps = no_notice_fee_bps and the middle band disappears.
ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS late_cancel_hours INT NOT NULL DEFAULT 2;

-- Between late_cancel_hours and free_cancel_hours. Default 0 = TODAY'S BEHAVIOUR, which is
-- deliberate: this migration must not silently start charging existing customers on existing
-- salons. Charging money is opt-in, per salon, through the owner's policy screen.
ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS late_cancel_fee_bps INT NOT NULL DEFAULT 0;

-- Below late_cancel_hours. Also defaults to 0 for the same reason.
ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS no_notice_fee_bps INT NOT NULL DEFAULT 0;

-- A fee over 100% is not a policy, it is a typo. Caught here rather than in a service method,
-- because the database is the one place no code path can route around.
ALTER TABLE salon_schema.salon_policy
    ADD CONSTRAINT chk_salon_policy_fee_bps
    CHECK (late_cancel_fee_bps BETWEEN 0 AND 10000 AND no_notice_fee_bps BETWEEN 0 AND 10000);

-- The bands must nest. late_cancel_hours above free_cancel_hours would make the middle band
-- negative-width and the tier logic would silently pick the wrong rate.
ALTER TABLE salon_schema.salon_policy
    ADD CONSTRAINT chk_salon_policy_cancel_window
    CHECK (late_cancel_hours >= 0 AND late_cancel_hours <= free_cancel_hours);

-- ---------------------------------------------------------------------------------------------
-- 2. Rescheduling
-- ---------------------------------------------------------------------------------------------

-- How much notice a CUSTOMER needs to move their own appointment. Darshan's example: "2 days or
-- 3 days or 1 day, depends on salon". Default 24h, matching the default free-cancel window —
-- so out of the box, "you can still change it" and "you can still cancel free" end together,
-- which is the easiest version to explain to a customer.
ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS reschedule_notice_hours INT NOT NULL DEFAULT 24;

-- Per BOOKING, not per customer.
--
-- A limit is needed because rescheduling is free and a slot held by a booking that moves every
-- week is a slot nobody else can take. 2 is generous enough that a real change of plans is
-- never blocked, and low enough that a booking cannot be parked indefinitely. 0 disables
-- customer rescheduling entirely for salons that would rather people just cancel and rebook.
ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS max_reschedules_per_booking INT NOT NULL DEFAULT 2;

ALTER TABLE salon_schema.salon_policy
    ADD CONSTRAINT chk_salon_policy_reschedule
    CHECK (reschedule_notice_hours >= 0 AND max_reschedules_per_booking BETWEEN 0 AND 10);

-- May the salon MOVE a booking, or only ASK?
--
-- FALSE by default, and that default is a product decision rather than a technical one. A salon
-- silently moving someone's Saturday morning is the kind of thing that gets discovered at the
-- door. Proposing it — the customer is notified and accepts — costs the salon one extra step and
-- is what a customer would expect a professional business to do.
--
-- TRUE exists because some salons genuinely operate that way (a stylist quits, everything shifts
-- an hour) and forcing forty individual approvals through the app would just push them to the
-- phone, where BMP has no record at all. Salon-initiated moves always notify the customer
-- either way; this column only decides whether the move waits for a yes.
ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS salon_can_reschedule_directly BOOLEAN NOT NULL DEFAULT FALSE;

-- The loophole guard. See the header. TRUE = the cancellation clock stays pinned to the
-- ORIGINAL appointment time no matter how many times the booking moves.
ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS reschedule_keeps_original_clock BOOLEAN NOT NULL DEFAULT TRUE;

-- ---------------------------------------------------------------------------------------------
-- NOT ADDED, deliberately: a per-salon "no-show fee".
--
-- A no-show is currently a salon-only claim with no dispute path (see NotificationDispatcher,
-- which sends nothing on NO_SHOW for exactly this reason). Attaching money to an unappealable
-- accusation is the wrong order to build things in. The fee tiers above only ever apply to an
-- explicit cancellation, which someone actively chose.
-- ---------------------------------------------------------------------------------------------
