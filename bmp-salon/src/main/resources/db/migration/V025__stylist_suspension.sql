-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V025 — barring a stylist from the platform, not just from one salon. Session 51.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── TWO DIFFERENT POWERS, DELIBERATELY SEPARATE ────────────────────────────────────────────────
-- A salon removing a stylist from its team is employment: the stylist_salon row becomes alumni,
-- and they are free to work anywhere else tomorrow. That is the salon's business and BMP has no
-- opinion about it.
--
-- Suspension is the platform's business, and it is a different claim: this person should not be
-- working through BMP AT ALL. It is for the cases a single salon cannot solve — someone who is
-- abusive across salons, who has been impersonating another stylist, or who is the subject of a
-- safety complaint.
--
-- Collapsing the two would mean either an admin cannot bar anyone, or a salon owner can bar
-- somebody from every OTHER salon by sacking them. Both are wrong.
--
-- ── WHY THIS LIVES ON `stylist` AND NOT ON `stylist_salon` ─────────────────────────────────────
-- Because it is a fact about the PERSON, not about one job. A suspended stylist with no current
-- salon must still be suspended when they later ask to join one — and a per-link flag could not
-- express that, because there is no link to put it on.
--
-- ── REVERSIBLE, AND THAT MATTERS ───────────────────────────────────────────────────────────────
-- Reinstating sets reinstated_at and LEAVES suspended_at in place. Currently-barred is therefore
-- the pair (suspended_at IS NOT NULL AND reinstated_at IS NULL), not a single nullable flag.
--
-- Suspensions get made on incomplete information — a complaint that turns out to be mistaken
-- identity, or one salon's grievance — and a bar that cannot be lifted means every borderline case
-- is either an over-punishment or a non-decision. Keeping both timestamps and the reason means the
-- record reads honestly afterwards: suspended on the 3rd for X, reinstated on the 9th.

ALTER TABLE salon_schema.stylist
    ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ;

-- WHY. Shown to the stylist, because a bar with no explanation is one they cannot contest, and
-- somebody whose livelihood is affected is owed a reason they can act on.
ALTER TABLE salon_schema.stylist
    ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(500);

-- WHO. A bmp_staff id. Every admin action in this system is attributable — the same rule the
-- audit log follows — and "who barred this person?" is the first question when it is disputed.
ALTER TABLE salon_schema.stylist
    ADD COLUMN IF NOT EXISTS suspended_by_staff_id UUID;

-- Set when the bar is lifted. suspended_at is NOT cleared — currently-suspended is the PAIR:
-- suspended_at IS NOT NULL AND reinstated_at IS NULL.
--
-- The first draft cleared suspended_at on reinstatement, which erased when the bar started and
-- violated the CHECK below (a reinstatement must have a suspension to point at). A test that
-- suspended and then reinstated caught it. Keeping both columns means the history reads:
-- suspended on the 3rd for X, reinstated on the 9th.
ALTER TABLE salon_schema.stylist
    ADD COLUMN IF NOT EXISTS reinstated_at TIMESTAMPTZ;

-- A suspension must say why. An unexplained bar is one the stylist cannot contest and support
-- cannot defend, and "we don't know, it was before my time" is not an answer anyone can give.
ALTER TABLE salon_schema.stylist
    ADD CONSTRAINT chk_stylist_suspension_reason
    CHECK (suspended_at IS NULL OR (suspension_reason IS NOT NULL AND length(trim(suspension_reason)) >= 5));

-- Reinstatement only follows a suspension. Written explicitly for both directions rather than as
-- `reinstated_at IS NULL OR suspended_at IS NOT NULL` — that reads the same and is fine, but the
-- vacuous-CHECK mistake was made twice in this project already (V023, and nearly in V008), so
-- these are now spelled out.
ALTER TABLE salon_schema.stylist
    ADD CONSTRAINT chk_stylist_reinstated
    CHECK ((reinstated_at IS NULL) OR (suspended_at IS NOT NULL AND reinstated_at >= suspended_at));

-- The console's "who is currently barred" list. Partial — suspensions are rare, so the index stays
-- small — and its predicate matches isSuspended() exactly: a suspension with no reinstatement.
CREATE INDEX IF NOT EXISTS idx_stylist_suspended
    ON salon_schema.stylist (suspended_at)
    WHERE suspended_at IS NOT NULL AND reinstated_at IS NULL;

COMMENT ON COLUMN salon_schema.stylist.suspended_at IS
    'Non-null = barred from BMP entirely: cannot be added to any salon, cannot be accepted from '
    'a join request, produces no bookable slots. A fact about the PERSON, not about one job — '
    'which is why it is here and not on stylist_salon. CURRENTLY suspended is the pair: '
    'suspended_at IS NOT NULL AND reinstated_at IS NULL. See V025.';
