-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V017 — a human-readable salon reference: BMPS001, BMPS002, … Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- THE PROBLEM
--
-- The salon's id is a UUIDv7: `01a044ad-0fc2-7110-8d1f-...`. That is correct as a primary key and
-- unusable as a reference. It went into the "we've received your application" email, where the
-- owner's realistic options are to squint at 36 characters or to give up. Nobody reads that number
-- down a phone line to support, and nobody quotes it correctly if they try.
--
-- BMPS001 is eight characters, says out loud in two seconds, and survives being written on a
-- notepad. The UUID stays exactly where it belongs — as the key, in URLs and in every API call.
-- This is a LABEL, not an identifier the system routes on.
--
-- WHY A SEQUENCE AND NOT count(*) + 1
--
-- The obvious implementation is "count the rows and add one". It is wrong, and wrong in a way that
-- is invisible until the day it matters: two salons created in the same moment both count N rows
-- and both take N+1. You get duplicate references, and the duplicates are in the one field support
-- uses to tell two salons apart.
--
-- A Postgres sequence is atomic, never reuses a number even on rollback, and needs no locking.
-- (bmp-admin's nextTicketRef still counts rows — same bug, still open, flagged in the backlog.)
--
-- Gaps are fine and expected. A rolled-back signup burns a number; BMPS004 not existing tells
-- nobody anything, and trying to avoid gaps is what forces you back to counting.

CREATE SEQUENCE IF NOT EXISTS salon_schema.salon_reference_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;   -- running out is a problem we would very much like to have

ALTER TABLE salon_schema.salon
    ADD COLUMN IF NOT EXISTS reference VARCHAR(16);

-- ── Backfill, oldest first ────────────────────────────────────────────────────────────────────
-- Ordered by created_at so the numbering matches the order salons actually joined. Assigning them
-- in whatever order the table scan returns would produce references that contradict the timeline,
-- which is worse than no reference at all — a salon numbered 001 that joined last is a fact nobody
-- can trust afterwards.
--
-- WHERE reference IS NULL keeps this re-runnable: rows that already have one are left alone, so
-- running the migration twice cannot renumber a salon whose reference is already in an owner's
-- inbox. A reference that changes is not a reference.
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM salon_schema.salon
    WHERE reference IS NULL
)
UPDATE salon_schema.salon s
SET reference = 'BMPS' || lpad(ordered.rn::text, 3, '0')
FROM ordered
WHERE s.id = ordered.id;

-- Move the sequence past everything the backfill just used, so the next INSERT cannot collide with
-- a backfilled row. setval with `false` means "the NEXT nextval returns exactly this".
SELECT setval(
    'salon_schema.salon_reference_seq',
    GREATEST((SELECT count(*) FROM salon_schema.salon), 0) + 1,
    false
);

-- ── Uniqueness ────────────────────────────────────────────────────────────────────────────────
-- The whole point is that one reference means one salon. Unique rather than merely indexed: a
-- duplicate here is a support incident, and the database is the only layer that can actually
-- promise it. Partial, because rows created between this migration and the code deploy may briefly
-- have NULL, and NULLs are not comparable anyway.
CREATE UNIQUE INDEX IF NOT EXISTS uq_salon_reference
    ON salon_schema.salon (reference)
    WHERE reference IS NOT NULL;

COMMENT ON COLUMN salon_schema.salon.reference IS
    'Human-readable salon reference (BMPS001). A LABEL for owners and support — never a key. '
    'Allocated from salon_reference_seq; see V017.';
