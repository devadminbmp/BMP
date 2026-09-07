-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V005 — real account anonymisation. Session 56.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── WHAT WAS ACTUALLY HAPPENING ────────────────────────────────────────────────────────────────
-- A data-deletion request under the DPDP Act (and GDPR, for anyone in scope) was being closed by
-- DEACTIVATING the account. `bmp-admin`'s DataRequestService said so in its own `action_taken`:
--
--     "Account deactivated. NOTE: full anonymisation is not implemented — personal fields remain
--      on the user record pending TODO(bmp-user) anonymise."
--
-- Honest, and still the wrong outcome: name, phone, email, gender, age, photo and hair profile all
-- stayed. Worse, deactivation is REVERSIBLE BY DESIGN — bmp-auth reactivates on the next
-- successful OTP login (V004, Instagram-style). So a "deleted" account came back if the person
-- ever logged in again, with every field intact.
--
-- ── WHY THE ROW SURVIVES ───────────────────────────────────────────────────────────────────────
-- The id is NOT deleted, deliberately. `booking.customer_id`, `invoice.customer_id`,
-- `review.author_user_id`, `coupon_usage.user_id` and `wallet.user_id` are cross-service logical
-- references with no foreign key to stop a delete orphaning them. Removing the row turns a real
-- appointment into a receipt with no customer and a paid invoice into an accounting hole.
--
-- Erasure obligations are about PERSONAL DATA, not about destroying the commercial record of a
-- transaction that genuinely happened — which tax law requires be kept anyway. So the identity is
-- erased and the skeleton stays.
--
-- ── THE PHONE IS THE HARD PART ─────────────────────────────────────────────────────────────────
-- `phone` is NOT NULL and UNIQUE (V004) because it is the login identity. It cannot be nulled and
-- it must not be left in place. It is replaced with a tombstone that:
--
--   · is unique, so the UNIQUE constraint holds;
--   · is not a dialable number, so nobody is ever contacted at it;
--   · is obviously not real to anyone reading the table; and
--   · FREES THE ORIGINAL NUMBER, so the same person can sign up again later as a new customer.
--     That last point matters: without it, erasing your account would permanently bar you from the
--     platform, which is a punishment for exercising a right.
--
-- Format: 'ANON-' plus the row's own id. Deterministic, collision-free, and self-explanatory.
--
-- ── anonymised_at IS NOT deactivated_at ────────────────────────────────────────────────────────
-- Two different states that must not be conflated:
--   deactivated_at  — reversible, user-initiated, restored on next login.
--   anonymised_at   — TERMINAL. There is nothing to restore; the data is gone.
-- bmp-auth must never reactivate an anonymised row, which is why this is a separate column and
-- not another meaning loaded onto the first one.

-- ── THE COLUMN HAS TO GROW FIRST ───────────────────────────────────────────────────────────────
-- `phone` is VARCHAR(20), which fits every real E.164 number and NOT the tombstone: 'ANON-' plus
-- a 36-character UUID is 41. Without this the anonymise would fail at runtime with
-- "value too long for type character varying(20)" — on the compliance path, at the worst moment.
--
-- Widening rather than shortening the tombstone on purpose. A truncated id (say the first 15 hex
-- characters) would still be unique in practice, but "unique by construction" would become
-- "unique probably", and the UNIQUE constraint on this column is what stops two erased accounts
-- colliding. 64 leaves room and costs nothing — varchar stores only what is written.
ALTER TABLE user_schema.users
    ALTER COLUMN phone TYPE VARCHAR(64);

ALTER TABLE user_schema.users
    ADD COLUMN IF NOT EXISTS anonymised_at TIMESTAMPTZ;

COMMENT ON COLUMN user_schema.users.anonymised_at IS
    'Non-null = personal data erased under a deletion request. TERMINAL and irreversible — unlike '
    'deactivated_at, which is a soft state reversed on the next successful login. bmp-auth must '
    'refuse to log in or reactivate a row where this is set. See V005.';

-- Records that erasure happened without keeping what was erased. The whole point is that the old
-- values are gone, so this stores only the FACT and who asked for it — enough to answer a
-- regulator's "when did you action this?" and nothing more.
ALTER TABLE user_schema.users
    ADD COLUMN IF NOT EXISTS anonymised_reason VARCHAR(40);

-- A phone freed by anonymisation can be claimed by a new signup, so uk_users_phone (V004) must
-- keep holding across tombstones. 'ANON-' + id is unique by construction; this index makes the
-- anonymised population cheap to count for a compliance report without scanning the table.
CREATE INDEX IF NOT EXISTS idx_users_anonymised
    ON user_schema.users (anonymised_at)
    WHERE anonymised_at IS NOT NULL;

-- An anonymised row must not still carry a name or an email. Enforced rather than trusted,
-- because the service method that does the erasing is exactly the kind of code that gets a new
-- field added to it and forgets one.
ALTER TABLE user_schema.users
    ADD CONSTRAINT chk_users_anonymised_is_empty CHECK (
        anonymised_at IS NULL
        OR (name IS NULL AND email IS NULL AND gender IS NULL
            AND profile_photo_url IS NULL AND hair_type IS NULL AND hair_length IS NULL
            AND phone LIKE 'ANON-%')
    );
