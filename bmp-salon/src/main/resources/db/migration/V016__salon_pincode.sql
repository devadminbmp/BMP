-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V016 — the salon's PIN code. Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- WHY THIS IS A COLUMN AND NOT PART OF `address`
--
-- The PIN code is already typed into the address field by most owners, buried in free text. That
-- makes it invisible to anything that needs it as a value:
--
--   · Customers search by PIN constantly — it is how people in Bengaluru actually describe where
--     they are ("anything in 560038?"). A substring match against a free-text address happens to
--     work today, but only by accident, and it also matches a house number that looks like one.
--   · Delivery of anything physical, GST paperwork and payout verification all need it separated.
--   · It is the cheapest sanity check we have on the map pin. A salon whose pin is 20km from the
--     centroid of its own PIN code has almost certainly dropped the marker in the wrong place, and
--     a wrong pin is the single most damaging data error in this product — nobody nearby finds them.
--
-- WHY IT IS NULLABLE
--
-- Additive and non-breaking, like every migration in this repo. Every salon that exists right now
-- has no PIN code, and refusing to start over that would take the service down. The signup form
-- requires it going forward; existing owners are asked for it the next time they edit their
-- profile. A NOT NULL here would have to be paired with a backfill we cannot honestly perform —
-- we do not know these salons' PIN codes, and inventing one is worse than leaving it empty.
--
-- WHY CHAR-ISH AND NOT AN INTEGER
--
-- Indian PIN codes are exactly six digits and never start with 0, so an INT would technically fit.
-- It is still the wrong type: a PIN code is an identifier, not a quantity. You never add two of
-- them. Storing it as text keeps it from being formatted with thousands separators by some
-- well-meaning client, and leaves room for the format to change without a type migration.
--
-- The CHECK enforces the real shape — six digits, first digit 1-9 — so a typo is rejected at the
-- boundary instead of quietly becoming a salon nobody can find.

ALTER TABLE salon_schema.salon
    ADD COLUMN IF NOT EXISTS pincode VARCHAR(6);

-- Named so a violation is greppable. Postgres has no ADD CONSTRAINT IF NOT EXISTS, so this is
-- wrapped to keep the migration re-runnable on a database where it was applied by hand.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_salon_pincode'
    ) THEN
        ALTER TABLE salon_schema.salon
            ADD CONSTRAINT chk_salon_pincode
            CHECK (pincode IS NULL OR pincode ~ '^[1-9][0-9]{5}$');
    END IF;
END $$;

-- Search hits this on every "560038" query. Partial index because most rows are NULL today and a
-- NULL entry helps nobody — it keeps the index to the rows that can actually match.
CREATE INDEX IF NOT EXISTS idx_salon_pincode
    ON salon_schema.salon (pincode)
    WHERE pincode IS NOT NULL;

COMMENT ON COLUMN salon_schema.salon.pincode IS
    'Six-digit Indian PIN code. Nullable for salons created before V016; required by the signup '
    'form from Session 48 onward. Searchable, and a cross-check on the map pin.';
