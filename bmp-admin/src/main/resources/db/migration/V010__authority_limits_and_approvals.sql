-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V010 — the authority matrix: who may do what, up to how much, and who signs off. Session 58.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE ASK ────────────────────────────────────────────────────────────────────────────────────
-- Darshan: *"support can give discount and cash coupons up to a certain level; if not, they pass
-- to a higher person. If a refund is required they pass to ops/finance. Even if ops admin can't,
-- they pass to admin. I've given the example only for discount coupons — next it can be
-- anything."*
--
-- The last sentence is the whole design brief. Coupons are ONE action. Building a bespoke ladder
-- for coupons, then another for refunds, then another for waivers, gives four subtly different
-- escalation rules that drift apart — and the fifth feature gets none at all because nobody
-- remembers the pattern.
--
-- So: ONE table describing every gated action, ONE table of pending approvals, ONE service. Adding
-- a new gated action is a row, not a code path.
--
-- ── WHY THE APPROVER IS NOT ALWAYS "ONE TIER UP" ───────────────────────────────────────────────
-- The obvious model — escalate to tier+1 — is wrong, and Darshan's own example says why: refunds
-- go to FINANCE, which is tier 0 and off the support ladder entirely (V009). Money is a different
-- axis from seniority. A refund does not become approvable by being escalated to a support lead;
-- it needs somebody who owns the money.
--
-- So each step names an approver by ROLE, and the steps form an ordered path. Coupons climb the
-- support ladder; refunds jump sideways to finance and then up to ops. Both are just rows.
--
-- ── VALUE-BANDED, WITH ZERO AS A REAL ANSWER ───────────────────────────────────────────────────
-- `max_value_paise = 0` means "this role may never do this at any amount", which is different from
-- absent. A support agent has a 0 band for refunds: they can REQUEST one and never issue one. The
-- distinction matters because "not configured" should fail closed rather than look like unlimited.
--
-- NULL means unbounded — reserved for the platform owner, who by definition has no ceiling.

-- ── 1. THE MATRIX ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS admin_schema.authority_limit (
    id UUID PRIMARY KEY NOT NULL,

    /*
     * What is being attempted. A stable code, never a display string — it appears in approval
     * rows that outlive any wording change, and it is what the service switches on to execute an
     * approved action.
     */
    action_type VARCHAR(60) NOT NULL,

    -- Who is attempting it.
    role VARCHAR(30) NOT NULL,

    /*
     * The ceiling for this role on this action, in paise.
     *
     *   NULL = no ceiling (the owner).
     *   0    = may never perform it, at any amount — but may REQUEST it.
     *   n    = may perform it up to n without asking anyone.
     *
     * Paise even for actions with no money attached (a suspension, an erasure); those use 0 or
     * NULL only, and the column stays one type rather than sprouting a second "is this monetary"
     * flag that every reader has to check.
     */
    max_value_paise BIGINT,

    /*
     * Who to ask when the value exceeds the ceiling. NULL = nobody above; the action is simply
     * refused rather than queued, which is the honest outcome at the top of the path.
     */
    approver_role VARCHAR(30),

    /*
     * Position in the path for this action, ascending. Two roles may share a step (finance and ops
     * can both clear a mid-size refund), and the service picks whoever is available — a queue that
     * can only be cleared by one specific person stalls when that person is on leave.
     */
    step_order SMALLINT NOT NULL DEFAULT 1,

    /*
     * Whether this role needs a linked ticket to act. Support goodwill must be attached to a real
     * complaint; an ops policy decision need not be.
     */
    requires_ticket BOOLEAN NOT NULL DEFAULT false,

    active BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_staff_id UUID
);

-- One ceiling per (action, role). Two rows for the same pair is two different answers to "may I?",
-- and whichever the query happens to return first wins — the exact class of bug this whole table
-- exists to prevent elsewhere.
CREATE UNIQUE INDEX IF NOT EXISTS uq_authority_action_role
    ON admin_schema.authority_limit (action_type, role);

ALTER TABLE admin_schema.authority_limit
    ADD CONSTRAINT chk_authority_value CHECK (max_value_paise IS NULL OR max_value_paise >= 0);

CREATE INDEX IF NOT EXISTS idx_authority_action
    ON admin_schema.authority_limit (action_type, step_order)
    WHERE active = true;

-- ── 2. THE PENDING APPROVAL ────────────────────────────────────────────────────────────────────
/*
 * One row per "I want to do X and I'm not allowed to".
 *
 * `payload` is JSONB rather than columns per action, and that is the point of the design: adding
 * "waive a cancellation fee" must not mean altering this table. The service that executes an
 * approved request knows the shape for its own action_type; nothing generic reads inside it.
 *
 * The trade-off is real and worth stating: JSONB means the database cannot validate the payload.
 * That is accepted because the alternative — a column per field per action — is a migration every
 * time the desk gains a power, and migrations are what stop people adding the row.
 */
CREATE TABLE IF NOT EXISTS admin_schema.approval_request (
    id UUID PRIMARY KEY NOT NULL,
    request_ref VARCHAR(24) NOT NULL,

    action_type VARCHAR(60) NOT NULL,

    -- What was asked for. Read only by the executor for this action_type.
    payload JSONB NOT NULL,

    -- The money at stake, lifted out of the payload so a queue can sort and filter by it without
    -- parsing JSON on every row.
    value_paise BIGINT NOT NULL DEFAULT 0,

    -- Who asked, and from where.
    requested_by_staff_id UUID NOT NULL,
    requested_by_role VARCHAR(30) NOT NULL,
    requested_by_tier SMALLINT NOT NULL,

    /*
     * The ticket this came out of. Required for support-originated goodwill (see
     * authority_limit.requires_ticket) — a discount with no complaint behind it is the thing that
     * turns a support desk into a leak.
     */
    ticket_id UUID REFERENCES admin_schema.support_ticket(id),

    -- REQUIRED. The approver reads this instead of reconstructing the case from the thread.
    justification TEXT NOT NULL,

    /*
     * Who can clear it right now. Moves up the path on each decline-and-escalate, so the row is
     * always addressed to somebody rather than sitting in a general pool nobody owns.
     */
    current_approver_role VARCHAR(30) NOT NULL,
    current_step SMALLINT NOT NULL DEFAULT 1,

    -- pending | approved | rejected | cancelled | executed | failed
    status VARCHAR(20) NOT NULL DEFAULT 'pending',

    decided_by_staff_id UUID,
    decided_at TIMESTAMPTZ,
    decision_note TEXT,

    /*
     * Approval and EXECUTION are separate states on purpose. An approved coupon still has to be
     * created, and that call can fail. Collapsing them would leave a request marked approved with
     * nothing issued — and the customer told they would receive something they never got.
     */
    executed_at TIMESTAMPTZ,
    execution_error TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_ref
    ON admin_schema.approval_request (request_ref);

ALTER TABLE admin_schema.approval_request
    ADD CONSTRAINT chk_approval_status CHECK (
        status IN ('pending', 'approved', 'rejected', 'cancelled', 'executed', 'failed'));

ALTER TABLE admin_schema.approval_request
    ADD CONSTRAINT chk_approval_justification CHECK (length(trim(justification)) >= 10);

-- A decided request must say who decided it. The vacuous-CHECK mistake from salon_schema V023 is
-- avoided by spelling out every branch rather than compressing to "status <> 'pending'".
ALTER TABLE admin_schema.approval_request
    ADD CONSTRAINT chk_approval_decided CHECK (
        (status = 'pending')
     OR (status = 'cancelled')
     OR (status IN ('approved', 'rejected', 'executed', 'failed')
         AND decided_by_staff_id IS NOT NULL AND decided_at IS NOT NULL)
    );

-- An executed request must have been approved first — nothing jumps straight to executed.
ALTER TABLE admin_schema.approval_request
    ADD CONSTRAINT chk_approval_executed CHECK (
        (status <> 'executed') OR (executed_at IS NOT NULL));

-- THE APPROVER'S QUEUE: everything waiting on my role, oldest first.
CREATE INDEX IF NOT EXISTS idx_approval_queue
    ON admin_schema.approval_request (current_approver_role, status, created_at)
    WHERE status = 'pending';

-- "What did I ask for, and did it land?" — the requester's own view.
CREATE INDEX IF NOT EXISTS idx_approval_requester
    ON admin_schema.approval_request (requested_by_staff_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_approval_ticket
    ON admin_schema.approval_request (ticket_id)
    WHERE ticket_id IS NOT NULL;

CREATE SEQUENCE IF NOT EXISTS admin_schema.approval_ref_seq START 1;

-- ── 3. THE DECISION TRAIL ──────────────────────────────────────────────────────────────────────
-- Append-only. A request declined by finance and then approved by ops has two rows, and the first
-- one is exactly the sort of record that gets tidied away when it becomes inconvenient.
CREATE TABLE IF NOT EXISTS admin_schema.approval_decision (
    id UUID PRIMARY KEY NOT NULL,
    request_id UUID NOT NULL REFERENCES admin_schema.approval_request(id),

    -- approved | rejected | escalated
    decision VARCHAR(20) NOT NULL,
    decided_by_staff_id UUID NOT NULL,
    decided_by_role VARCHAR(30) NOT NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE admin_schema.approval_decision
    ADD CONSTRAINT chk_decision_kind CHECK (decision IN ('approved', 'rejected', 'escalated'));

CREATE INDEX IF NOT EXISTS idx_decision_request
    ON admin_schema.approval_decision (request_id, created_at);

-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- 4. THE SEEDED MATRIX
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- Every number here is a Darshan-only default and carries the same ratification flag as the rest
-- of this repo. They are DATA, so changing one is an audited update by a super_admin rather than a
-- deploy — which is the point of putting them in a table at all.
--
-- Read a column of rows for one action as the path a request walks.

-- Ids are FIXED, not generated. Two reasons: `gen_random_uuid()` needs pgcrypto on older
-- PostgreSQL and nothing else in this repo depends on it, and a deterministic id means re-running
-- this on a fresh environment produces the same rows — so two environments can be diffed, and
-- ON CONFLICT below is genuinely idempotent rather than accidentally inserting duplicates with
-- new ids if the unique index were ever dropped.
INSERT INTO admin_schema.authority_limit
    (id, action_type, role, max_value_paise, approver_role, step_order, requires_ticket)
VALUES
    -- ── coupon.issue — Darshan's worked example ────────────────────────────────────────────────
    -- Support hands out goodwill up to ₹500 on a real complaint. Above that a lead, then ops, then
    -- the owner. Matches the CouponIssuePolicy ceilings already in bmp-rewards.
    ('f22126c2-602a-5dc6-b434-8bcb2cdedfb7', 'coupon.issue',  'support_agent',  50000,  'support_lead', 1, true),
    ('a75b8a4d-82f2-50e6-820f-8941a3f7e947', 'coupon.issue',  'support_lead',  200000,  'ops_admin',    2, true),
    ('d7351241-b49f-5ffe-afba-1cf5bf359a58', 'coupon.issue',  'ops_admin',    1000000,  'super_admin',  3, false),
    ('cc896308-b67c-5bfc-8887-6db2385acbd4', 'coupon.issue',  'super_admin',      NULL,  NULL,          4, false),

    -- ── refund.issue — the sideways jump ───────────────────────────────────────────────────────
    -- Support and leads are ZERO: they raise a refund and never issue one. It is real money
    -- leaving the business, and the person comforting an upset customer is the worst-placed person
    -- to decide it. Finance clears up to ₹10,000; beyond that ops; beyond that the owner.
    ('7f18be69-69e7-5101-8b48-5fb44e574497', 'refund.issue',  'support_agent',      0,  'finance_admin', 1, true),
    ('7178f73d-312f-5b86-9ee5-fdf6753a6d47', 'refund.issue',  'support_lead',       0,  'finance_admin', 1, true),
    ('473afe88-e809-51a4-94d0-a2ce57d664b2', 'refund.issue',  'finance_admin', 1000000, 'ops_admin',     2, false),
    ('13e0b1ed-0f39-5319-8087-c69fc52c317f', 'refund.issue',  'ops_admin',     5000000, 'super_admin',   3, false),
    ('a795a5bd-a746-53a0-b6b1-fa73d781e901', 'refund.issue',  'super_admin',      NULL,  NULL,           4, false),

    -- ── wallet.credit — goodwill that is not a coupon ──────────────────────────────────────────
    -- Tighter than coupons: wallet credit is spendable anywhere with no minimum spend and no
    -- expiry pressure, so it is closer to cash than a discount code is.
    ('5bf15ab4-0b63-5614-a8af-dbf2da9b5bdb', 'wallet.credit', 'support_agent',  20000,  'support_lead',  1, true),
    ('cb37cc22-6340-5174-b70e-7bd31ea78e51', 'wallet.credit', 'support_lead',  100000,  'ops_admin',     2, true),
    ('fd09bd74-55c3-5bc6-a990-4c3e7c7c1907', 'wallet.credit', 'ops_admin',    1000000,  'super_admin',   3, false),
    ('910012a6-5d8b-57a0-b218-c1619049eaa0', 'wallet.credit', 'super_admin',      NULL,  NULL,           4, false),

    -- ── booking.waive_fee — a cancellation fee forgiven ────────────────────────────────────────
    -- Support can waive a small fee outright: it is the commonest goodwill gesture and routing it
    -- upward would queue a ₹100 decision behind a ₹50,000 one.
    ('5bae9866-00dd-5e1b-b32c-fdf8e5307db9', 'booking.waive_fee', 'support_agent', 30000, 'support_lead', 1, true),
    ('826c3836-1340-5215-8b51-edaad7f6bc2c', 'booking.waive_fee', 'support_lead', 150000, 'ops_admin',    2, true),
    ('83027800-11ef-5766-ac8c-30d4f5877f60', 'booking.waive_fee', 'ops_admin',       NULL, NULL,          3, false),
    ('7d719b23-f98d-5fb4-bee8-0e98a0f0feba', 'booking.waive_fee', 'super_admin',     NULL, NULL,          4, false),

    -- ── salon.suspend — not about money at all ─────────────────────────────────────────────────
    -- Taking a business off the platform stops its income. Zero for support and leads: they raise
    -- it with evidence, ops decides. Demonstrates that the same machinery gates non-monetary acts.
    ('12d2498d-1c97-50b8-93a6-642e59a0ed5c', 'salon.suspend', 'support_agent',      0,  'ops_admin',     1, true),
    ('b4cf43b8-14cb-51cf-a47d-a49f4de09918', 'salon.suspend', 'support_lead',       0,  'ops_admin',     1, true),
    ('e31308ce-2344-5845-aea5-859b6839a337', 'salon.suspend', 'ops_admin',       NULL,  NULL,            2, false),
    ('f185e9ee-021f-5040-98e6-05e14314b756', 'salon.suspend', 'super_admin',     NULL,  NULL,            3, false),

    -- ── stylist.suspend — same shape, same reasoning ───────────────────────────────────────────
    ('290b38e1-0aa1-5946-b78e-f2523c5b72fa', 'stylist.suspend', 'support_agent',    0,  'ops_admin',     1, true),
    ('11b14f6e-c586-50cb-b285-9d618ac42a08', 'stylist.suspend', 'support_lead',     0,  'ops_admin',     1, true),
    ('f6d60513-90c6-5c63-8dbc-2a30c0dde238', 'stylist.suspend', 'ops_admin',     NULL,  NULL,            2, false),
    ('4d8b9ddc-568c-50e8-b316-15ccea23c6f8', 'stylist.suspend', 'super_admin',   NULL,  NULL,            3, false),

    -- ── user.anonymise — irreversible, so it stops one rung short of everybody ─────────────────
    -- Ops can action a verified erasure request; support cannot, at any value. There is no undo.
    ('d636c70b-6e14-5334-8e31-f54a936e982e', 'user.anonymise', 'support_agent',     0,  'ops_admin',     1, true),
    ('1ba4401f-34fe-517b-93ba-a057cb29a0f4', 'user.anonymise', 'support_lead',      0,  'ops_admin',     1, true),
    ('8cdb231e-9e51-5e80-acde-2d68963cbc8f', 'user.anonymise', 'ops_admin',      NULL,  NULL,            2, false),
    ('db778f09-93a4-5a0c-b886-d0d207aae83e', 'user.anonymise', 'super_admin',    NULL,  NULL,            3, false),

    -- ── commission.adjust — the salon's economics ──────────────────────────────────────────────
    -- Nobody below ops touches what a salon is charged. A support agent renegotiating commission
    -- to settle a complaint would be changing a contract to end a conversation.
    ('86aa980b-c4a8-5f98-8742-04a6ca0a9d7a', 'commission.adjust', 'ops_admin',   NULL,  'super_admin',   1, false),
    ('26acbb13-0973-5684-921d-ff109e808b33', 'commission.adjust', 'super_admin', NULL,  NULL,            2, false)
ON CONFLICT (action_type, role) DO NOTHING;

COMMENT ON TABLE admin_schema.authority_limit IS
    'Who may perform which action, up to what value, and who signs off above it. ONE table for '
    'every gated action — adding a new one is a row, not a code path. The approver is named by '
    'ROLE, not by tier+1, because refunds go sideways to finance rather than up the support '
    'ladder. See V010.';

COMMENT ON TABLE admin_schema.approval_request IS
    'A pending "I want to do X and I am not allowed to". payload is JSONB so a new gated action '
    'needs no migration; only the executor for that action_type reads inside it. Approval and '
    'execution are separate states because an approved action can still fail. See V010.';
