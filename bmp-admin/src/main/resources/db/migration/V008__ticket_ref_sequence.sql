-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V008 — ticket references from a SEQUENCE, not a row count. Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- THE BUG
--
-- SupportTicketService.nextTicketRef() built the reference by counting:
--
--     "TCK-2026-" + lpad(countByTicketRefStartingWith("TCK-2026-") + 1, 5)
--
-- Two tickets created in the same moment both count N and both take N+1. The unique index then
-- rejects one of them, so a customer raising a support ticket gets a 500 — and the ones that do
-- get through can collide across a year boundary or after a delete.
--
-- Duplicates land in the one field support uses to tell two tickets apart. It is the same bug the
-- salon reference had (fixed in bmp-salon's V017), and it has been carrying a "known, not fixed"
-- comment for several sessions. Real users can raise tickets now, so it is no longer theoretical.
--
-- WHY A SEQUENCE
--
-- nextval() is atomic, never returns the same number twice, and needs no locking or retry. It is
-- the boring correct answer; counting rows is the appealing wrong one.
--
-- Gaps are expected and fine — a rolled-back insert burns a number. TCK-2026-00004 not existing
-- tells nobody anything, and trying to avoid gaps is exactly what forces you back to counting.
--
-- ── WHY THE YEAR IS NOT IN THE SEQUENCE ────────────────────────────────────────────────────────
-- The reference keeps its "TCK-<year>-" prefix, built in Java, while the number comes from one
-- sequence that never resets. So the first ticket of 2027 might be TCK-2027-00412 rather than
-- 00001.
--
-- That is deliberate. Resetting per year means either a scheduled job (a thing to forget) or
-- reading the max for the current year (counting again, with the same race). A number that only
-- ever increases cannot collide, and nobody has ever needed a ticket reference to start at 1.

CREATE SEQUENCE IF NOT EXISTS admin_schema.support_ticket_ref_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;

-- ── Move it past anything that already exists ─────────────────────────────────────────────────
-- Existing references were allocated by the old counter, so the sequence must start above the
-- highest number already used or the first new ticket collides with an old one — reintroducing
-- the exact bug this migration removes, on its first use.
--
-- The regex pulls the numeric tail off any 'TCK-YYYY-NNNNN'. Rows that do not match that shape
-- (there should be none) are ignored rather than crashing the migration.
SELECT setval(
    'admin_schema.support_ticket_ref_seq',
    GREATEST(
        COALESCE((
            SELECT MAX(substring(ticket_ref FROM '[0-9]+$')::bigint)
            FROM admin_schema.support_ticket
            WHERE ticket_ref ~ '^TCK-[0-9]{4}-[0-9]+$'
        ), 0),
        0
    ) + 1,
    false   -- false: the NEXT nextval() returns exactly this value
);

COMMENT ON SEQUENCE admin_schema.support_ticket_ref_seq IS
    'Allocates the numeric part of a support ticket reference. Replaces a count(*)+1 that could '
    'hand two concurrent tickets the same reference. Never resets — see V008.';
