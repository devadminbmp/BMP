-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- V016 — LEAVE PLANS AND ENTITLEMENTS. The main admin as HR authority. Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- Darshan, Session 65:
--   "he allocate leave plans for each like sick annual etc because still we dont have hr profile
--    main admin can do it ... he can see how many leaves each have taken and what dates etc by
--    everyone even support"
--
-- ── WHAT EXISTED, AND WHAT WAS MISSING ─────────────────────────────────────────────────────────
-- V011 gave us staff_leave: request, approve, reject, cancel, and a history. That answers "did
-- this person take leave". It does NOT answer "were they ENTITLED to it", because nothing in the
-- database says how many days anybody gets.
--
-- Without entitlements, "approve" is a decision made from memory, and a balance shown anywhere in
-- the console would be a number somebody made up. Two tables fix that.
--
-- ── ONE: leave_plan — the ORG-WIDE default, per role ───────────────────────────────────────────
-- "A support agent gets 12 casual and 8 sick days a year." Set once, applies to everyone in that
-- role, and is what a new hire inherits on day one without anybody remembering to configure them.
--
-- ── TWO: leave_entitlement — the PER-PERSON number ─────────────────────────────────────────────
-- The plan is the default; this is the truth for one individual. It exists because real HR is full
-- of exceptions — somebody negotiated 18 days, somebody joined in November and gets a pro-rated
-- allowance, somebody was granted extra after a hard quarter.
--
-- WHY BOTH, rather than only per-person rows: with only entitlements, adding a role-wide day means
-- editing every employee and missing the one hired yesterday. With only plans, one exception means
-- inventing a fake role. The resolution order is entitlement → plan → zero, and `source` records
-- which one a row came from so an override is visibly deliberate rather than indistinguishable
-- from a materialised default.
--
-- ── THE LEAVE YEAR IS APRIL–MARCH ──────────────────────────────────────────────────────────────
-- Darshan's call, Session 65: the Indian financial year. FY 2026-27 runs 2026-04-01 to 2027-03-31
-- and is stored as `fy_start_year = 2026`.
--
-- Stored as the START year, one integer, rather than two dates: a row per year with explicit dates
-- invites overlapping or gapped periods that then have to be validated, and "which entitlement
-- applies on 2026-03-31" becomes a range query with an off-by-one at every boundary. One integer
-- has exactly one interpretation, computed identically everywhere by LeaveYear.java.
--
-- ── WHY days_allowed IS NUMERIC(4,1) AND NOT AN INTEGER ────────────────────────────────────────
-- Half-days. V011 already models them (staff_leave.half_day), so a balance that counts only whole
-- days would drift the first time somebody leaves at lunch — and drift in the employee's favour,
-- silently, forever. 0.5 is representable; 12.5 days of casual leave is a real number.
--
-- NUMERIC, not DOUBLE PRECISION: 0.1 + 0.2 must equal 0.3 in something people are paid against.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

-- ── 0. `annual` joins the leave types ──────────────────────────────────────────────────────────
--
-- V011 allowed casual, sick, unpaid and comp_off. Darshan asked for "sick annual etc", and annual
-- (earned/privilege leave) is a distinct thing from casual in Indian practice: casual is a day or
-- two at short notice, annual accrues and is taken in blocks.
--
-- DROP and re-ADD rather than a second CHECK: two constraints on the same column both have to pass,
-- so leaving the old one in place would silently continue rejecting 'annual' while the new one
-- accepted it — and the error message would name the old constraint, sending whoever debugs it to
-- the wrong migration.
ALTER TABLE admin_schema.staff_leave DROP CONSTRAINT IF EXISTS chk_leave_type;
ALTER TABLE admin_schema.staff_leave
    ADD CONSTRAINT chk_leave_type
    CHECK (leave_type IN ('casual', 'sick', 'annual', 'unpaid', 'comp_off'));


-- ── 1. THE PLAN ────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_schema.leave_plan (
    id UUID PRIMARY KEY NOT NULL,

    -- A CONSOLE role: super_admin, admin, ops_admin, support_lead, support_agent, finance_admin,
    -- read_only. No FK — roles are Java constants (RoleHierarchy), not rows. See the note in V015
    -- on why the role vocabulary is not a database CHECK.
    role VARCHAR(20) NOT NULL,

    -- casual | sick | annual | comp_off. Deliberately NOT `unpaid`: unpaid leave has no ceiling to
    -- allocate — that is what makes it unpaid — so a plan row for it would be meaningless.
    leave_type VARCHAR(20) NOT NULL,

    fy_start_year INT NOT NULL,

    days_allowed NUMERIC(4,1) NOT NULL,

    /*
     * Days that survive into next year, capped.
     *
     * Default 0 — "use it or lose it" — because carry-forward is a liability that accumulates
     * quietly: an unlimited carry-forward is an unbounded payout the day somebody leaves. Set it
     * deliberately or not at all.
     */
    carry_forward_max NUMERIC(4,1) NOT NULL DEFAULT 0,

    set_by_staff_id UUID REFERENCES admin_schema.bmp_staff(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One answer per (role, type, year). Two rows would be two different entitlements for the same
-- person and whichever the query returned first would win — the class of bug this table exists to
-- remove from somebody's memory.
CREATE UNIQUE INDEX IF NOT EXISTS uq_leave_plan
    ON admin_schema.leave_plan (role, leave_type, fy_start_year);

ALTER TABLE admin_schema.leave_plan
    ADD CONSTRAINT chk_leave_plan_days CHECK (days_allowed >= 0 AND days_allowed <= 365);

ALTER TABLE admin_schema.leave_plan
    ADD CONSTRAINT chk_leave_plan_carry CHECK (carry_forward_max >= 0 AND carry_forward_max <= days_allowed);

ALTER TABLE admin_schema.leave_plan
    ADD CONSTRAINT chk_leave_plan_type CHECK (leave_type IN ('casual', 'sick', 'annual', 'comp_off'));

-- Half-days are the only fraction anybody takes. This rejects 12.3 — a typo, not a policy.
ALTER TABLE admin_schema.leave_plan
    ADD CONSTRAINT chk_leave_plan_halves CHECK (
        (days_allowed * 2) = floor(days_allowed * 2)
        AND (carry_forward_max * 2) = floor(carry_forward_max * 2));

COMMENT ON TABLE admin_schema.leave_plan IS
    'Org-wide leave entitlement per console role per financial year (Apr-Mar, stored as start '
    'year). The DEFAULT a person inherits; leave_entitlement overrides it individually. Set by the '
    'main admin only — Session 65.';


-- ── 2. THE PERSON ──────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_schema.leave_entitlement (
    id UUID PRIMARY KEY NOT NULL,

    staff_id UUID NOT NULL REFERENCES admin_schema.bmp_staff(id),
    leave_type VARCHAR(20) NOT NULL,
    fy_start_year INT NOT NULL,

    days_allowed NUMERIC(4,1) NOT NULL,

    /*
     * WHY this number is what it is. 'plan' or 'override'.
     *
     * A row written by copying the role's plan and a row somebody typed deliberately are the same
     * number and completely different facts. Without this column, re-running a plan rollout would
     * either overwrite a negotiated exception or skip everybody — and there would be no way to
     * tell which rows were safe to touch.
     */
    source VARCHAR(10) NOT NULL DEFAULT 'plan',

    /* Why the exception exists. Free text, and worth having: an override with no reason is one
     * nobody can defend six months later when the person who granted it has left. */
    note VARCHAR(300),

    set_by_staff_id UUID REFERENCES admin_schema.bmp_staff(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_leave_entitlement
    ON admin_schema.leave_entitlement (staff_id, leave_type, fy_start_year);

-- The HR overview reads a whole year at a time, for everybody.
CREATE INDEX IF NOT EXISTS idx_leave_entitlement_year
    ON admin_schema.leave_entitlement (fy_start_year, staff_id);

ALTER TABLE admin_schema.leave_entitlement
    ADD CONSTRAINT chk_entitlement_days CHECK (days_allowed >= 0 AND days_allowed <= 365);

ALTER TABLE admin_schema.leave_entitlement
    ADD CONSTRAINT chk_entitlement_source CHECK (source IN ('plan', 'override'));

ALTER TABLE admin_schema.leave_entitlement
    ADD CONSTRAINT chk_entitlement_type CHECK (leave_type IN ('casual', 'sick', 'annual', 'comp_off'));

ALTER TABLE admin_schema.leave_entitlement
    ADD CONSTRAINT chk_entitlement_halves CHECK ((days_allowed * 2) = floor(days_allowed * 2));

COMMENT ON TABLE admin_schema.leave_entitlement IS
    'One person''s leave allowance for one type in one financial year. Overrides leave_plan. '
    'source=override marks a deliberate exception so a plan rollout can leave it alone. Session 65.';

COMMENT ON COLUMN admin_schema.leave_entitlement.source IS
    'plan = copied from the role default and safe to refresh. override = somebody decided this '
    'individually and a rollout must not touch it.';


-- ── 3. A STARTING PLAN, SO THE SCREEN IS NOT EMPTY ─────────────────────────────────────────────
--
-- Seeded for FY 2026 (Apr 2026 - Mar 2027) because a balance screen showing "0 of 0" for everybody
-- on day one reads as broken, and the first thing anyone does with a broken-looking screen is stop
-- using it.
--
-- These numbers are a STARTING POINT, not a policy — the main admin edits them on the HR screen
-- and the edit is what makes them real. Roughly standard Indian practice: 12 casual, 12 sick,
-- 15 annual for everybody, with annual carrying up to 5 days forward. Senior roles get the same:
-- entitlement is not a perk ladder, and giving the owner more leave than the desk is a decision
-- somebody should have to make on purpose rather than inherit from a migration.
INSERT INTO admin_schema.leave_plan (id, role, leave_type, fy_start_year, days_allowed, carry_forward_max)
SELECT
    -- Deterministic ids: re-running on a fresh environment produces the same rows, so two
    -- environments can be diffed. Same reasoning as V010's authority matrix.
    md5('bmp.leave_plan.' || r.role || '.' || t.leave_type || '.2026')::uuid,
    r.role, t.leave_type, 2026, t.days, t.carry
FROM (VALUES
        ('super_admin'), ('admin'), ('ops_admin'),
        ('support_lead'), ('support_agent'), ('finance_admin'), ('read_only')
     ) AS r(role),
     (VALUES
        ('casual',   12.0, 0.0),
        ('sick',     12.0, 0.0),
        ('annual',   15.0, 5.0),
        -- Comp-off is EARNED by working a day off, never allocated in advance. Zero is the correct
        -- allowance; the balance for it comes from grants, which is a later feature. Seeded at 0
        -- rather than omitted so the type appears on the screen as a known, deliberate zero
        -- instead of silently missing.
        ('comp_off',  0.0, 0.0)
     ) AS t(leave_type, days, carry)
ON CONFLICT (role, leave_type, fy_start_year) DO NOTHING;
