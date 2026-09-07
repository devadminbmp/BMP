-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- V006 — a review can be hidden by moderation. Session 60.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- THE GAP THIS CLOSES
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- `ConsoleController.resolveContentReport` carried this, honestly, for several sessions:
--
--     TODO(bmp-review / bmp-salon): upholding should also hide the content. Recording the
--     decision without acting on it means a moderator marks something as removed and it
--     stays visible.
--
-- Which is the worst shape a moderation tool can have. The moderator does the work, the audit log
-- says the content was removed, the reporter is told it was handled — and the abusive review is
-- still on the salon's page. Nobody finds out until the reporter reports it again, and by then the
-- queue says it was already dealt with, so the second report looks like a duplicate.
--
-- HIDDEN, NOT DELETED
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- Three reasons the row stays:
--
--   1. Moderation is reversible. Upholding a report is a judgement call made in seconds, and the
--      appeal is "put it back" — which is impossible if the text is gone.
--   2. `booking_id` is UNIQUE on this table. Deleting a review would silently let the same customer
--      write a second one for the same appointment, which is how a hidden review becomes a fresh
--      review with the same content.
--   3. The rating still happened. Whether hidden reviews should count toward a salon's average is
--      a real question with two defensible answers; deleting the row answers it permanently and
--      invisibly. See the note on the filter below for what this migration actually chose.
--
-- WHAT THIS MIGRATION DOES *NOT* DO
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- It does not recompute any salon rating snapshot. That is an aggregation job's business, and a
-- migration quietly rewriting denormalised ratings is the kind of change nobody can trace later.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE review_schema.review
    ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS hidden_reason VARCHAR(500),
    -- A staff id from admin_schema. Not an FK: different service, different database boundary.
    ADD COLUMN IF NOT EXISTS hidden_by_staff_id UUID;

/*
 * Hidden means all three are set, or none are.
 *
 * A row with `hidden_at` and no reason is a review that vanished with no explanation — and the
 * first question anybody asks about hidden content is "why, and who". Enforcing it here means the
 * answer always exists, rather than depending on every future caller remembering to pass it.
 */
ALTER TABLE review_schema.review
    DROP CONSTRAINT IF EXISTS chk_review_hidden_complete;
ALTER TABLE review_schema.review
    ADD CONSTRAINT chk_review_hidden_complete CHECK (
        (hidden_at IS NULL AND hidden_reason IS NULL AND hidden_by_staff_id IS NULL)
        OR (hidden_at IS NOT NULL AND hidden_reason IS NOT NULL AND hidden_by_staff_id IS NOT NULL)
    );

/*
 * Every public read filters on `hidden_at IS NULL`, so it belongs in the index those reads use.
 *
 * Partial rather than a plain column index: hidden reviews are a tiny minority and are never the
 * thing being listed, so indexing only the visible rows keeps the index the size of the result set
 * it serves.
 */
CREATE INDEX IF NOT EXISTS idx_review_salon_visible
    ON review_schema.review (salon_id, created_at DESC)
    WHERE hidden_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_stylist_visible
    ON review_schema.review (stylist_id)
    WHERE hidden_at IS NULL AND stylist_rating IS NOT NULL;

COMMENT ON COLUMN review_schema.review.hidden_at IS
    'Set when moderation upheld a report against this review. The row is kept: moderation is '
    'reversible, booking_id is unique so deleting would allow a duplicate review, and the '
    'rating is history. Every customer-facing read filters on this being NULL.';
