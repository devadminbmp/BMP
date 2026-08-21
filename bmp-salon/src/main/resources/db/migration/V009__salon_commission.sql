-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- V009 — the salon's commission rate becomes DATA instead of a constant in Java.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- WHY
-- BookingService.create computed commission as `total.percentBps(1200)` — a hardcoded 12%,
-- identical for every salon, frozen onto every booking. Two problems with that:
--
--   1. Commission is a NEGOTIATED number. An anchor salon that brings volume gets a different
--      rate to a new one, and a launch offer ("no commission for your first month") is the most
--      obvious thing a founder will want to do to sign the first ten salons. None of that is
--      expressible when the rate lives in a compiled constant.
--
--   2. Changing it meant a code deploy, which meant nobody would change it, which meant the
--      first commercial conversation with a salon owner would have ended in "we can't do that".
--
-- IN BASIS POINTS, NOT A PERCENTAGE OR A DECIMAL
-- 1200 bps = 12.00%. Integer arithmetic throughout, exactly like every money column in this
-- platform is integer paise. A DECIMAL(5,2) rate invites floating-point drift into the one
-- calculation where a rounding error is somebody's income; bps keeps it exact and matches
-- Money.percentBps(), which already exists in bmp-common and is already used here.
--
-- DEFAULT 1200 IS DELIBERATE AND IS NOT A DECISION
-- It preserves today's behaviour exactly, so this migration changes no existing number. It is
-- NOT an agreed commercial rate — no salon has agreed to anything yet (see docs/PENDING_WORK.md
-- and the Track 0 item "agree commission % with 3 real owners"). Whoever holds that
-- conversation should expect to change this per salon, which is now a row update rather than a
-- release.
--
-- ADDITIVE ONLY. Adds a column with a default; no existing row changes value, nothing is
-- dropped or renamed, and a running old build ignores the column. Safe to apply before deploy.
-- ═══════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE salon_schema.salon_policy
    ADD COLUMN IF NOT EXISTS commission_bps INT NOT NULL DEFAULT 1200;

-- Guard rails rather than a comment nobody reads. 0 is legitimate (a launch offer); anything
-- above 50% is somebody typing a percentage into a basis-points field, which is exactly the
-- mistake this unit choice invites and the only one worth catching at the database.
ALTER TABLE salon_schema.salon_policy
    ADD CONSTRAINT chk_salon_policy_commission_bps
    CHECK (commission_bps >= 0 AND commission_bps <= 5000);

COMMENT ON COLUMN salon_schema.salon_policy.commission_bps IS
    'Platform commission in basis points (1200 = 12.00%). Negotiated per salon. Snapshotted onto every booking at creation — changing it never alters an existing booking.';
