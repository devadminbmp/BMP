-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V011 — running the team: assignment modes, employee records, and leave. Session 59.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- Three requests, one migration, because they are the same subject: the people who work here.
--
--   1. "who will assign the tickets can be a business analyst or automatically or manual also"
--   2. "I need an employee management system for my team members, including leaves of them"
--   3. all the authority ranges "can be fixed by admin owner of BMP"  → V010's table, made editable
--
-- ── 1. HOW TICKETS GET ASSIGNED ────────────────────────────────────────────────────────────────
-- V009 auto-assigns to the least-loaded person at the right tier. That is the right DEFAULT and the
-- wrong ONLY option: a small desk wants a human triaging, a large one wants the machine doing it,
-- and a team with a dedicated analyst wants everything landing on them to distribute by hand.
--
-- So the mode is configuration, not a code path. All three modes use the same queue and the same
-- assignment service — they differ only in whether, and by whom, the assignee is chosen.
--
--   auto     — least-loaded at the ticket's tier, on arrival. What V009 does today.
--   manual   — nobody is assigned; the pool is worked by whoever picks something up.
--   analyst  — assigned to ONE named person, who distributes. The "business analyst" model.
--
-- Stored per TIER, not globally: L1 is high-volume and suits auto; an ops queue is low-volume and
-- high-stakes and often wants a human deciding who takes it.

CREATE TABLE IF NOT EXISTS admin_schema.queue_config (
    id UUID PRIMARY KEY NOT NULL,

    -- 1..4, matching bmp_staff.tier. One row per tier.
    tier SMALLINT NOT NULL,

    -- auto | manual | analyst
    assignment_mode VARCHAR(20) NOT NULL DEFAULT 'auto',

    /*
     * Required when mode = 'analyst'. The person everything at this tier lands on.
     *
     * Nullable rather than NOT NULL because the column is meaningless in the other two modes, and
     * a placeholder id would be a lie that some later query joins on.
     */
    analyst_staff_id UUID,

    /*
     * Stop handing work to somebody already drowning. 0 = no cap.
     *
     * Auto-assignment without a cap will happily give the least-loaded person their fortieth
     * ticket when everyone is at forty — which looks like fair distribution and is actually a desk
     * that has stopped coping. A cap makes the overflow visible in the unassigned pool instead.
     */
    max_open_per_agent INT NOT NULL DEFAULT 0,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_staff_id UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_queue_config_tier
    ON admin_schema.queue_config (tier);

ALTER TABLE admin_schema.queue_config
    ADD CONSTRAINT chk_queue_mode CHECK (assignment_mode IN ('auto', 'manual', 'analyst'));

ALTER TABLE admin_schema.queue_config
    ADD CONSTRAINT chk_queue_tier CHECK (tier BETWEEN 1 AND 4);

-- 'analyst' with nobody named would silently assign nothing, which looks exactly like 'manual'
-- while claiming to be something else.
ALTER TABLE admin_schema.queue_config
    ADD CONSTRAINT chk_queue_analyst CHECK (
        assignment_mode <> 'analyst' OR analyst_staff_id IS NOT NULL);

ALTER TABLE admin_schema.queue_config
    ADD CONSTRAINT chk_queue_cap CHECK (max_open_per_agent >= 0);

-- Every tier starts on 'auto' — the behaviour V009 already had, so this migration changes nothing
-- until somebody deliberately switches a queue over.
INSERT INTO admin_schema.queue_config (id, tier, assignment_mode)
VALUES ('a1b2c3d4-0000-4000-8000-000000000001', 1, 'auto'),
       ('a1b2c3d4-0000-4000-8000-000000000002', 2, 'auto'),
       ('a1b2c3d4-0000-4000-8000-000000000003', 3, 'auto'),
       ('a1b2c3d4-0000-4000-8000-000000000004', 4, 'auto')
ON CONFLICT (tier) DO NOTHING;

-- ── 2. THE EMPLOYEE RECORD ─────────────────────────────────────────────────────────────────────
/*
 * `bmp_staff` already holds what the SYSTEM needs: email, password, role, tier, status. What it
 * lacks is what a MANAGER needs — when somebody joined, who they report to, what they are called
 * on the floor.
 *
 * Added to the existing table rather than a parallel `employee` one. A second table keyed to the
 * same person is two rows that drift: somebody gets deactivated in one and not the other, and the
 * team list and the login list stop agreeing about who works here.
 *
 * DELIBERATELY ABSENT: salary, bank details, government identifiers. Those belong in a payroll
 * system with a different access model, and putting them one JOIN away from a support console —
 * where five roles can already read staff rows — is how a console permission becomes a payroll
 * breach. If payroll is wanted later it should be its own service.
 */
ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS employee_code VARCHAR(20);

ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS joined_on DATE;

ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS reports_to_staff_id UUID;

ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS job_title VARCHAR(80);

-- Working hours, so "why is nobody answering at 9pm" has an answer on a screen rather than in
-- somebody's memory. Free text on purpose: shifts vary and a structured rota is a feature nobody
-- has asked for yet.
ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS shift_note VARCHAR(120);

ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS exited_on DATE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_staff_employee_code
    ON admin_schema.bmp_staff (employee_code)
    WHERE employee_code IS NOT NULL;

-- Somebody who has left cannot have joined after they left.
ALTER TABLE admin_schema.bmp_staff
    ADD CONSTRAINT chk_staff_dates CHECK (
        exited_on IS NULL OR joined_on IS NULL OR exited_on >= joined_on);

-- The org chart: "who reports to me". Cheap for a team page that would otherwise scan.
CREATE INDEX IF NOT EXISTS idx_staff_reports_to
    ON admin_schema.bmp_staff (reports_to_staff_id)
    WHERE reports_to_staff_id IS NOT NULL;

-- ── 3. LEAVE ───────────────────────────────────────────────────────────────────────────────────
/*
 * Deliberately the SAME SHAPE as stylist leave (salon_schema V023): request, decide, and the
 * approval is what makes it real. Two leave systems in one platform with different rules is two
 * things to learn and two places to fix a bug.
 *
 * The important difference from stylist leave: approved staff leave FLIPS accepting_tickets OFF for
 * the duration. Stylist leave removes bookable slots; staff leave must remove somebody from the
 * assignment rotation, or the queue keeps handing tickets to a person on holiday and the customer
 * waits a week for a first reply.
 */
CREATE TABLE IF NOT EXISTS admin_schema.staff_leave (
    id UUID PRIMARY KEY NOT NULL,
    staff_id UUID NOT NULL REFERENCES admin_schema.bmp_staff(id),

    -- casual | sick | unpaid | comp_off. A code, not a display string.
    leave_type VARCHAR(20) NOT NULL,

    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,

    /*
     * Half a day. Nullable = the whole day.
     *
     * Included because a half-day is the commonest leave anybody takes and modelling it as a
     * one-day absence overstates the gap in cover — which then reads as a staffing problem on the
     * team screen when it is somebody leaving at lunch.
     */
    half_day VARCHAR(10),

    reason VARCHAR(500),

    -- pending | approved | rejected | cancelled
    status VARCHAR(20) NOT NULL DEFAULT 'pending',

    decided_by_staff_id UUID,
    decided_at TIMESTAMPTZ,
    decision_note VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE admin_schema.staff_leave
    ADD CONSTRAINT chk_leave_type CHECK (leave_type IN ('casual', 'sick', 'unpaid', 'comp_off'));

ALTER TABLE admin_schema.staff_leave
    ADD CONSTRAINT chk_leave_half CHECK (half_day IS NULL OR half_day IN ('morning', 'afternoon'));

ALTER TABLE admin_schema.staff_leave
    ADD CONSTRAINT chk_leave_dates CHECK (ends_on >= starts_on);

-- A half-day spanning a range is a contradiction — it means "half of which day?".
ALTER TABLE admin_schema.staff_leave
    ADD CONSTRAINT chk_leave_half_single_day CHECK (
        half_day IS NULL OR starts_on = ends_on);

ALTER TABLE admin_schema.staff_leave
    ADD CONSTRAINT chk_leave_status CHECK (
        status IN ('pending', 'approved', 'rejected', 'cancelled'));

/*
 * A decided request must record who decided it — spelled out per branch rather than compressed to
 * `status <> 'pending'`, which is trivially true for every non-pending row and therefore enforces
 * nothing. Exactly the vacuous CHECK written in salon_schema V023 and caught by a test.
 */
ALTER TABLE admin_schema.staff_leave
    ADD CONSTRAINT chk_leave_decided CHECK (
        (status = 'pending')
     OR (status = 'cancelled')
     OR (status IN ('approved', 'rejected')
         AND decided_by_staff_id IS NOT NULL AND decided_at IS NOT NULL)
    );

-- "Who is off this week?" — the query the team calendar runs.
CREATE INDEX IF NOT EXISTS idx_leave_window
    ON admin_schema.staff_leave (starts_on, ends_on)
    WHERE status = 'approved';

CREATE INDEX IF NOT EXISTS idx_leave_staff
    ON admin_schema.staff_leave (staff_id, starts_on DESC);

-- The approver's inbox.
CREATE INDEX IF NOT EXISTS idx_leave_pending
    ON admin_schema.staff_leave (status, created_at)
    WHERE status = 'pending';

COMMENT ON TABLE admin_schema.staff_leave IS
    'Time off for BMP staff. Same shape as stylist leave (salon_schema V023) on purpose. Approved '
    'leave flips bmp_staff.accepting_tickets off for the duration — otherwise the queue keeps '
    'assigning to somebody on holiday and a customer waits a week for a first reply. See V011.';

COMMENT ON TABLE admin_schema.queue_config IS
    'How tickets are assigned, per tier: auto (least-loaded), manual (unassigned pool) or analyst '
    '(one named person distributes). Configuration rather than a code path — all three use the '
    'same queue and the same assignment service. See V011.';
