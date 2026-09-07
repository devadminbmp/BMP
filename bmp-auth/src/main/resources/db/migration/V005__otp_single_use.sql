-- V005__otp_single_use.sql  (Session 43)
--
-- WHY
-- ───
-- Until now a verified OTP stayed valid for the remainder of its 5-minute TTL. Nothing marked
-- the row as spent, so the same six digits could be replayed any number of times — by whoever
-- held them. Today the only delivery channel is email, so "whoever holds them" includes anyone
-- with a glance at an inbox, a forwarded message, or a shared screen. Single-use is the whole
-- point of a one-time password; without this column the "one-time" part was aspirational.
--
-- WHAT
-- ────
-- consumed_at: when this code was successfully redeemed. NULL = never used.
-- We record the timestamp rather than a boolean because "when" answers questions a flag can't:
-- how long codes sit unused, and whether a replay attempt arrived seconds or minutes after the
-- legitimate login. Costs the same 8 bytes either way.
--
-- Nullable with no default and no backfill, on purpose. Existing rows are all expired (TTL is
-- 5 minutes and this deploys long after they were written), so backfilling them would invent a
-- consumption event that never happened. NULL here honestly means "we don't know / never used".
--
-- Additive and immutable, per the migration rules in CONTEXT.md — new column, no edit to V002.
ALTER TABLE user_schema.otp_requests
    ADD COLUMN consumed_at TIMESTAMPTZ;

COMMENT ON COLUMN user_schema.otp_requests.consumed_at IS
    'When this OTP was successfully redeemed. NULL = unused. Set by AuthService.verifyOtp; a '
    'non-NULL value makes the code permanently unusable even if expires_at is still in the future.';

-- Partial index: every verify does "is this row already consumed?", and the rows we care about
-- are the live, unconsumed ones. Indexing only WHERE consumed_at IS NULL keeps the index small
-- (unused codes are a tiny, self-clearing slice of the table) while still covering the lookup
-- AuthService actually performs — newest row for a phone.
CREATE INDEX idx_otp_requests_phone_live
    ON user_schema.otp_requests (phone, created_at DESC)
    WHERE consumed_at IS NULL;
