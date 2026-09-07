-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V021 — a stylist works at ONE salon at a time. Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE RULE ───────────────────────────────────────────────────────────────────────────────────
-- One ACTIVE stylist_salon row per stylist. They can have as many alumni rows as they have had
-- jobs — that is their history and it is the point — but only one place they work right now.
--
-- ── WHY THE DATABASE AND NOT JUST THE SERVICE ──────────────────────────────────────────────────
-- StylistSelfService checks before accepting a join request, and StaffService checks before
-- consuming an invite. Two code paths, both able to create the link, and a third (a manual fix, a
-- data migration, a future feature) is always one commit away.
--
-- More concretely: those checks are read-then-write. Two owners accepting the same stylist at the
-- same moment both read "no active salon" and both insert. The window is small and the failure is
-- silent — the stylist appears on two salons' teams, two calendars, and two booking pickers, and
-- the first anyone knows is a double-booking. A unique index cannot lose that race.
--
-- ── WHY IT IS PARTIAL ──────────────────────────────────────────────────────────────────────────
-- ON (stylist_id) WHERE status = 'active'. A stylist who worked at three salons over five years
-- has three rows; only the current one is active. A full unique index would make leaving and
-- rejoining impossible, which is precisely the history this design exists to keep.
--
-- ── BACKFILL ───────────────────────────────────────────────────────────────────────────────────
-- Any stylist who is currently active at more than one salon keeps the one they joined MOST
-- RECENTLY and the rest become alumni. That is a guess, and it is the least-bad one: the newest
-- link is the likeliest to be their real current job, and alumni is not a deletion — the row and
-- its ratings survive, and an owner can re-add them in one tap if the guess was wrong.
--
-- Doing nothing is not an option: the index cannot be created while duplicates exist, so the
-- migration would fail on any real database that has them.

-- Demote everything except each stylist's newest active link.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY stylist_id
               -- joined_at can be null on older rows; fall back to the row id, which is a UUIDv7
               -- and therefore time-ordered. Never arbitrary.
               ORDER BY COALESCE(joined_at, '-infinity'::timestamptz) DESC, id DESC
           ) AS rn
    FROM salon_schema.stylist_salon
    WHERE status = 'active'
)
UPDATE salon_schema.stylist_salon s
SET status = 'alumni',
    left_at = COALESCE(s.left_at, now())
FROM ranked
WHERE s.id = ranked.id
  AND ranked.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_stylist_one_active_salon
    ON salon_schema.stylist_salon (stylist_id)
    WHERE status = 'active';

COMMENT ON INDEX salon_schema.uq_stylist_one_active_salon IS
    'A stylist works at one salon at a time. Alumni rows are unlimited — that is their work '
    'history, and it is deliberately preserved when they leave. See V021.';
