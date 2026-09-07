-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V022 — how far ahead customers can book, and how close to the slot. Session 49.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE HORIZON ────────────────────────────────────────────────────────────────────────────────
-- booking_horizon_days: the salon says "open my calendar N days ahead" and slots beyond that
-- simply do not exist for a customer. 1 = today and tomorrow only; 30 = a month out.
--
-- Why a salon wants this at all: a small salon that takes bookings six months ahead ends up
-- honouring prices, staff and opening hours it set in another season. Most want a short, rolling
-- window they can actually staff.
--
-- Default 30. Chosen so this migration changes NOTHING for existing salons — before today there
-- was no horizon and the customer app offers a 30-day date strip, so 30 reproduces exactly the
-- behaviour they have now. A smaller default would silently close bookings that salons are
-- currently taking, which is a migration doing business harm.
--
-- ── THE MINIMUM NOTICE, AND WHY IT DEFAULTS TO ZERO ────────────────────────────────────────────
-- min_notice_minutes: refuse slots starting sooner than this from now.
--
-- The obvious default is "2 hours, so nobody books a colour ten minutes before it starts". That
-- default is wrong for this product. Darshan's objection, and he is right: somebody with a
-- function tonight who needs a haircut NOW is exactly the customer a walk-in-heavy Bengaluru
-- salon wants, and a platform-imposed two-hour wait sends them to the salon next door instead.
--
-- So it exists as a lever and defaults to 0 — no wait at all unless a salon chooses one. A salon
-- that does colour and needs prep time can set 120; a barber who will take you off the street
-- leaves it at 0. The platform does not decide.
--
-- ── WHY BOTH LIVE ON salon_policy ──────────────────────────────────────────────────────────────
-- salon_policy is already the per-salon booking-rules row (cancellation window, grace period,
-- slot granularity) and is already loaded by AvailabilityService on every slot query. Adding two
-- integers costs nothing at read time and keeps "the rules for booking here" in one place.

ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS booking_horizon_days INT NOT NULL DEFAULT 30;

ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS min_notice_minutes INT NOT NULL DEFAULT 0;

-- Bounds, not preferences. 0 horizon days would close the salon entirely while looking like a
-- configuration choice; 365 is a year, past which the "prices I set in another season" problem
-- is certain rather than likely.
ALTER TABLE salon_schema.salon_policy
    ADD CONSTRAINT chk_policy_horizon_days
    CHECK (booking_horizon_days BETWEEN 1 AND 365);

-- Up to 7 days of notice. Above that the horizon is the better tool, and a salon that has set
-- notice > horizon has accidentally closed its own calendar — see SalonService.upsertPolicy,
-- which refuses that combination with an explanation rather than leaving them to discover it.
ALTER TABLE salon_schema.salon_policy
    ADD CONSTRAINT chk_policy_min_notice
    CHECK (min_notice_minutes BETWEEN 0 AND 10080);

COMMENT ON COLUMN salon_schema.salon_policy.booking_horizon_days IS
    'Customers can book up to N days ahead (today = day 0). Default 30 = the behaviour before '
    'V022, so this migration changes nothing for existing salons.';

COMMENT ON COLUMN salon_schema.salon_policy.min_notice_minutes IS
    'Refuse slots starting sooner than this from now. DEFAULT 0 on purpose — an urgent haircut '
    'is a customer, not a problem. Salons that need prep time set their own value.';
