-- V007__salon_review_resubmission.sql  (Session 46)
--
-- ═════════════════════════════════════════════════════════════════════════════════════════════
-- A rejected salon can fix what was wrong and come back.
-- ═════════════════════════════════════════════════════════════════════════════════════════════
--
-- Today rejection is a dead end. `SalonModerationService.decide` refuses to re-decide anything
-- that isn't pending, with a comment that is exactly right about why:
--
--     "Re-approving a rejection would erase the fact it was ever rejected."
--
-- That guard must stay. Most rejections, though, are fixable in ten minutes — a blurry photo, a
-- missing street address, the map pin dropped on the wrong side of the road. With no way back,
-- every one of those becomes a support ticket or, worse, a partner who gives up. So the answer
-- is not to relax the guard; it is to stop reusing the row.
--
-- ---------------------------------------------------------------------------------------------
-- ONE ROW PER SUBMISSION, NOT ONE ROW PER SALON
-- ---------------------------------------------------------------------------------------------
-- `uk_salon_review_salon` made salon_id unique, so a salon had exactly one review row forever and
-- a resubmission could only be expressed by overwriting the decision that already existed —
-- destroying who rejected it, when, and why. That history is the thing a second reviewer most
-- needs, and it is the thing an owner disputing a decision most needs.
--
-- So the unique index goes and each submission gets its own row. Nothing is ever overwritten:
-- the rejection stays on its own row with its note and its reviewer, and the new attempt is a
-- new row that starts pending. `decide`'s guard keeps working unchanged, because it now only
-- ever sees the row it was handed.
--
-- The cost is that "the review for this salon" becomes "the LATEST review for this salon", and
-- every caller has to say so. That is a real cost and it is the right one — the alternative is a
-- table that quietly forgets.
--
-- IDEMPOTENCY, which the unique index was also doing:
-- `enqueue` is called by bmp-salon on salon creation and its javadoc says "whatever triggers it
-- will eventually fire twice". The index used to absorb that. It is now expressed properly, as
-- "create a row only if this salon has no PENDING row" — which is what idempotent actually meant
-- here, and unlike the index it doesn't also forbid the legitimate second submission.
-- ---------------------------------------------------------------------------------------------

DROP INDEX IF EXISTS admin_schema.uk_salon_review_salon;

-- The salon's whole review history, newest first. Replaces the unique index for lookups.
CREATE INDEX IF NOT EXISTS idx_salon_review_salon_history
    ON admin_schema.salon_review (salon_id, submitted_at DESC);

-- Which attempt this is. Denormalised so the console can show "2nd submission" without counting
-- rows per salon on every render of the queue — and so an owner's repeated failed attempts are
-- visible at a glance, which is a signal a moderator should have.
ALTER TABLE admin_schema.salon_review
    ADD COLUMN IF NOT EXISTS submission_count INT NOT NULL DEFAULT 1;

-- What the owner changed before resubmitting, in their words.
--
-- Not decoration: a reviewer looking at a second attempt needs to know what to re-check. Without
-- it they re-review the whole salon from scratch, which is slow enough that resubmissions get
-- deprioritised — and a resubmission queue that moves slower than the new-salon queue punishes
-- exactly the partners who did what we asked.
ALTER TABLE admin_schema.salon_review
    ADD COLUMN IF NOT EXISTS resubmission_note TEXT;

COMMENT ON COLUMN admin_schema.salon_review.submission_count IS
    'Which attempt this row represents. 1 for a first submission. A salon may now have several '
    'review rows — one per submission — so that a rejection is never overwritten by a retry.';

COMMENT ON COLUMN admin_schema.salon_review.resubmission_note IS
    'What the owner says they fixed. NULL on a first submission. Lets a reviewer re-check the '
    'specific thing rather than starting over.';

COMMENT ON COLUMN admin_schema.salon_review.decision_note IS
    'The moderator''s reason. REQUIRED on rejection (min 10 chars, enforced in the service) and '
    'since Session 46 SHOWN TO THE OWNER verbatim — it is the only thing telling them what to '
    'fix. Write it for them, not for an internal audit trail.';
