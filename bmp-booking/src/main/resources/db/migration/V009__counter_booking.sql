-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V009 — bookings taken at the counter or over the phone. Session 52.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE GAP ────────────────────────────────────────────────────────────────────────────────────
-- Darshan: *"suppose any customer calls the manager or comes to walk in, then the manager should
-- update it in the portal and the manager should select the stylist… we should compulsorily have
-- their data in our database — remember, it's their own customer."*
--
-- Today the manager's only tool is `walk_in_block`: it takes the stylist's time off the calendar
-- and records nothing else. No customer, no services, no price, no invoice, no history. For most
-- salons that is the MAJORITY of their trade, and it is invisible to the platform.
--
-- `POST /bookings` could not be used, because `customer_id NOT NULL` means a BMP user account, and
-- a person standing at the counter does not have one.
--
-- ── WHY customer_id BECOMES NULLABLE ───────────────────────────────────────────────────────────
-- The alternative was to silently create a `users` row for every walk-in. That would mean the
-- platform quietly acquiring accounts for people who never signed up, never consented, and would
-- receive notifications from a brand they have never heard of. See V026 in salon_schema for the
-- same reasoning about the same person.
--
-- So a booking now has exactly ONE of two identities:
--   · customer_id        — a real BMP account, made through the app.  source = 'online'
--   · salon_customer_id  — the salon's own contact record (V026).     source = 'counter'
--
-- The CHECK below makes that an invariant rather than a convention, because "exactly one of these
-- is set" is precisely the kind of rule that decays into "sometimes neither" without one.
--
-- ── WHAT NULL customer_id DOES TO EXISTING CODE ────────────────────────────────────────────────
-- Every ownership check in BookingController is of the form `caller.userId().equals(customerId)`.
-- With a null customer_id that comparison is FALSE, so a counter booking is not readable by any
-- customer — it fails CLOSED, which is the correct direction. `findByCustomerId` simply never
-- returns these rows, so "My bookings" is unaffected.
--
-- The salon-side reads are all salon-scoped and keep working, which is the point: the salon sees
-- its own counter bookings on the same calendar as its online ones.
--
-- ── source IS NOT COSMETIC ─────────────────────────────────────────────────────────────────────
-- It decides who gets notified (no push to a person with no app), whether online payment applies
-- (counter trade is settled at the counter), and what commission conversation the salon has. It is
-- NOT derivable from "customer_id is null" for long — a linked counter customer will eventually
-- have both — so it is stored explicitly.

ALTER TABLE booking_schema.booking
    ALTER COLUMN customer_id DROP NOT NULL;

-- Which door this booking came through. Defaulted so every existing row is correctly 'online'.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS source VARCHAR(10) NOT NULL DEFAULT 'online';

-- salon_schema.salon_customer.id. NOT a foreign key: that table lives in another service's schema
-- and another service's deployment, and a cross-service FK turns one service's migration into the
-- other's outage. Referential integrity here is the counter-booking endpoint's job, which creates
-- the salon_customer row itself immediately before the booking.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS salon_customer_id UUID;

-- Who at the salon took it down. Useful when a customer disputes a price a receptionist quoted.
ALTER TABLE booking_schema.booking
    ADD COLUMN IF NOT EXISTS taken_by_staff_id UUID;

ALTER TABLE booking_schema.booking
    ADD CONSTRAINT chk_booking_source CHECK (source IN ('online', 'counter'));

-- Exactly one identity. Written explicitly per branch rather than as a clever XOR, because the
-- vacuous-CHECK mistake in salon_schema V023 came from exactly that kind of compression.
ALTER TABLE booking_schema.booking
    ADD CONSTRAINT chk_booking_identity CHECK (
        (source = 'online'  AND customer_id IS NOT NULL AND salon_customer_id IS NULL)
     OR (source = 'counter' AND customer_id IS NULL     AND salon_customer_id IS NOT NULL)
    );

-- A counter booking has no account to read the name from, so the snapshot columns added in V006
-- are the ONLY record of who this is. Enforced for counter rows only; online rows resolve the
-- name from bmp-user and may legitimately have had it fail.
ALTER TABLE booking_schema.booking
    ADD CONSTRAINT chk_counter_has_contact CHECK (
        source <> 'counter'
     OR (customer_name IS NOT NULL AND length(trim(customer_name)) >= 1
         AND customer_phone IS NOT NULL AND length(trim(customer_phone)) >= 10)
    );

-- "Show me every visit this person has made here" — the counter's repeat-customer view.
CREATE INDEX IF NOT EXISTS idx_booking_salon_customer
    ON booking_schema.booking (salon_customer_id, created_at DESC)
    WHERE salon_customer_id IS NOT NULL;

COMMENT ON COLUMN booking_schema.booking.source IS
    'online = booked in the app by a BMP account; counter = taken at the salon by staff for '
    'someone with no account. Decides notification, payment and identity rules. See V009.';

COMMENT ON COLUMN booking_schema.booking.salon_customer_id IS
    'salon_schema.salon_customer.id — the SALON''s own contact record, not a BMP user. No FK by '
    'design: cross-service, cross-schema. See V009 and salon_schema V026.';
