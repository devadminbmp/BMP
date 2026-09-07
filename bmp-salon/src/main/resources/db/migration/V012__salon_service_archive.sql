-- V012__salon_service_archive.sql  (Session 44)
--
-- WHY THERE IS NO DELETE
-- ──────────────────────
-- The owner needs to retire a service — "we stopped doing keratin". The obvious implementation
-- is DELETE, and it is wrong three times over:
--
--   1. salon_schema.stylist_service and salon_schema.salon_combo_item hold REAL foreign keys to
--      salon_service.id. A delete either fails on the constraint or, with a cascade, silently
--      removes a stylist's skill list and guts a combo the owner never touched.
--
--   2. booking_schema.booking_service_item.service_id is a LOGICAL ref across a service
--      boundary — no FK, so nothing stops the delete and nothing reports the damage. Every past
--      booking would point at an id that no longer resolves. It would look fine (bookings freeze
--      name/price at creation) right up until someone asks "what did we sell most of last year".
--
--   3. A price list is a commercial record. "We charged ₹4,500 for this" is a fact about the
--      past, and the past should not become editable because the present changed.
--
-- So: archived_at. The service stops being bookable and stops appearing on the menu; every row
-- that ever referenced it still resolves. Reversible, which DELETE never is.
--
-- Nullable, no default, no backfill: NULL means live, and every existing service IS live.
ALTER TABLE salon_schema.salon_service
    ADD COLUMN archived_at TIMESTAMPTZ;

COMMENT ON COLUMN salon_schema.salon_service.archived_at IS
    'When the salon retired this service. NULL = live and bookable. Archived services stay '
    'readable so past bookings, combos and stylist skill lists keep resolving; they are hidden '
    'from the customer menu and rejected for new bookings.';

-- The customer menu asks "what can I book here?" on every salon page load, and the answer is
-- always the live subset. Partial index so it stays small as retired rows accumulate.
CREATE INDEX idx_salon_service_live
    ON salon_schema.salon_service (salon_id)
    WHERE archived_at IS NULL;
