-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V018 — 'approved' and 'live' become different things. Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- BEFORE: SalonService.PUBLICLY_VISIBLE was ('approved', 'active'). A moderator's approval put the
-- salon into customer search immediately — often with no services, no stylists and no opening
-- hours, because the owner had not finished setting up. Customers found a listing they could not
-- book, which is worse than not finding it at all: it spends the salon's first impression on a
-- dead end.
--
-- AFTER:
--   pending    submitted, waiting for review        — not visible
--   approved   passed review, owner not ready yet   — NOT VISIBLE  ← the change
--   active     owner pressed Go Live                — visible and bookable
--   rejected / suspended                            — not visible
--
-- The owner decides when they open. That is the same decision a real salon makes about its own
-- shutter, and it is theirs to make.
--
-- ── THE DANGEROUS PART OF THIS MIGRATION ──────────────────────────────────────────────────────
-- Narrowing PUBLICLY_VISIBLE means every salon currently sitting at 'approved' would VANISH from
-- search the moment the new code deploys — without anybody touching them, and with no event to
-- explain it. A schema change that silently unpublishes live businesses is not acceptable.
--
-- So they are promoted to 'active' here. Those salons were visible and bookable before this
-- migration; they stay visible and bookable after it. The new rule applies only to salons approved
-- from now on, which is the only group that can be given the choice.
--
-- Deliberately NOT touching pending/rejected/suspended: none of them were visible, so none of them
-- can be made worse by a change to what "visible" means.

UPDATE salon_schema.salon
SET status = 'active',
    updated_at = now()
WHERE status = 'approved';

-- When the owner went live. NULL for a salon that has never published.
--
-- Separate from status rather than derived from it, because status moves both ways: a salon that
-- goes live, is suspended, and is later restored should still know it has published before —
-- that is the difference between "welcome, here's how to open" and "you're back". Deriving it from
-- the current status loses that history the first time the status changes.
ALTER TABLE salon_schema.salon
    ADD COLUMN IF NOT EXISTS went_live_at TIMESTAMPTZ;

-- Backfill for everything already live, so no existing salon is treated as never-published.
-- updated_at is the closest honest approximation we have; we did not record the real moment.
UPDATE salon_schema.salon
SET went_live_at = COALESCE(updated_at, created_at)
WHERE status = 'active' AND went_live_at IS NULL;

COMMENT ON COLUMN salon_schema.salon.went_live_at IS
    'When the owner first pressed Go Live. NULL = never published. Kept across suspension so a '
    'restored salon is not greeted as a new one. See V018.';
