-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- V004 — asking for a coupon you're not allowed to issue yourself.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- WHAT V003 GOT RIGHT, AND WHAT IT LEFT OUT
-- V003 gave support hard per-coupon limits (₹500, 20%, 1 recipient, 30 days) and made them
-- configuration rather than constants. That is the right wall. What it had no answer for is the
-- question that follows within a week of going live:
--
--     "This customer's wedding booking was cancelled by the salon. ₹500 isn't enough.
--      What do I do?"
--
-- Today the answer is "nothing" — the agent hits a 403 and the conversation stops. In practice
-- that means one of two things happens, and both are worse than the problem:
--   · the agent gives up and the customer leaves, or
--   · someone shares an admin login "just for this one".
--
-- A refusal with no path forward doesn't enforce a policy. It routes around it.
--
-- THE OTHER GAP: AGGREGATE SPEND
-- Per-coupon limits are not a budget. One agent could issue a hundred ₹500 coupons in an
-- afternoon — every one of them individually within policy — and nobody would know until the
-- month's numbers came in. `coupon_allowance_override` plus the new policy rows close that:
-- there is now a per-period ceiling on COUNT and on TOTAL VALUE, and exceeding it routes to the
-- same request flow rather than failing.
--
-- SALONS CAN ASK TOO
-- A salon owner wanting to run "20% off Tuesdays in February" has no way to create it — they
-- can't be given coupon-creation rights, because a coupon can be funded by BMP's commission
-- (see commission_base). So they raise a request, BMP decides who pays, and the coupon is
-- created centrally. This table serves both requesters; `requester_type` is the only difference.
--
-- WHY NOT REUSE support_ticket
-- A ticket is a conversation with a customer. This is an internal approval with a structured
-- payload that becomes a row in `coupon` on approval. Forcing it into the ticket table would
-- mean parsing a coupon out of free text, which is how approvals get mis-keyed.
-- ═══════════════════════════════════════════════════════════════════════════════════════════

-- --------------------------------------------------------------------------------------------
-- 1. The request
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rewards_schema.coupon_request (
    id UUID PRIMARY KEY NOT NULL,
    -- Human reference. What an agent quotes to an admin on a call: "CR-2026-00042".
    request_ref VARCHAR(24) NOT NULL,

    -- ---- who is asking -------------------------------------------------------------------
    -- staff | salon_owner. The two come in through different doors and hold different tokens;
    -- this column is what lets one queue serve both.
    requester_type VARCHAR(20) NOT NULL,
    requester_id UUID NOT NULL,
    -- Denormalised so the row stays readable after someone leaves the company or the platform.
    requester_name VARCHAR(160),
    requester_email VARCHAR(160),
    -- Resolved from bmp-user when the request is raised, so a decision can be delivered without
    -- an outbound call inside the approval transaction. Nullable: staff have no phone on file.
    requester_phone VARCHAR(20),
    -- The role AT THE TIME of asking — 'support_agent', or 'salon_owner'.
    requester_role VARCHAR(30),
    -- Required when requester_type = 'salon_owner'; optional for staff (a support agent may be
    -- asking on behalf of one salon's customer).
    salon_id UUID,

    -- ---- why ------------------------------------------------------------------------------
    -- NOT NULL on purpose. An approver deciding on money needs the reason in the same screen,
    -- and "requested by Priya" is not a reason. The service also enforces a minimum length —
    -- a required field that accepts "." is a required field in name only.
    justification TEXT NOT NULL,
    -- The ticket this settles, for support-raised goodwill. Null for campaigns.
    issued_for_ticket_id UUID,

    -- ---- what is being asked for ----------------------------------------------------------
    -- Mirrors rewards_schema.coupon. Deliberately a copy rather than a JSON blob: an admin
    -- filters this queue by value and expiry, and you cannot index or sanely query a blob.
    proposed_name VARCHAR(120),
    audience_type VARCHAR(20) NOT NULL,
    salon_scope VARCHAR(20) NOT NULL DEFAULT 'all_salons',
    discount_type VARCHAR(10) NOT NULL,          -- flat | percent
    value BIGINT NOT NULL,                        -- paise if flat, basis points if percent
    max_discount_paise BIGINT,                    -- required for percent; see CouponIssuePolicy
    min_spend_paise BIGINT NOT NULL DEFAULT 0,
    per_user_limit INT NOT NULL DEFAULT 1,
    total_usage_cap INT,
    active_from TIMESTAMPTZ NOT NULL,
    active_to TIMESTAMPTZ NOT NULL,
    -- pre_discount = the salon funds it; post_discount = BMP does. The funding question, which
    -- is exactly why a salon owner cannot answer it for themselves.
    commission_base VARCHAR(15) NOT NULL DEFAULT 'post_discount',

    -- ---- the decision ---------------------------------------------------------------------
    -- pending | approved | rejected | cancelled | expired
    --
    -- 'cancelled' is the requester withdrawing ("sorted it another way") — distinct from
    -- 'rejected', because one is a refusal and the other isn't, and conflating them makes the
    -- approval-rate number meaningless.
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    decided_by_staff_id UUID,
    decided_by_email VARCHAR(160),
    decided_at TIMESTAMPTZ,
    decision_note TEXT,

    -- APPROVE WITH MODIFICATION. An approver who can only say yes or no says no.
    -- "You asked for ₹2,000; here's ₹800" is the answer most of the time, and without these
    -- columns it has to be a rejection followed by a re-request, which nobody does — they just
    -- approve the ₹2,000. Null means "granted exactly as asked".
    approved_value BIGINT,
    approved_max_discount_paise BIGINT,
    approved_active_to TIMESTAMPTZ,

    -- Set when approval mints the coupon. The link that turns this table into an audit trail:
    -- every coupon above an agent's limit can be traced to who approved it and why.
    created_coupon_id UUID,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_request_ref ON rewards_schema.coupon_request(request_ref);
-- The queue, in the order an admin works it: oldest pending first.
CREATE INDEX IF NOT EXISTS idx_coupon_request_status ON rewards_schema.coupon_request(status, created_at);
-- "What have I asked for?" — the requester's own list.
CREATE INDEX IF NOT EXISTS idx_coupon_request_requester ON rewards_schema.coupon_request(requester_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_coupon_request_salon ON rewards_schema.coupon_request(salon_id, created_at DESC);

-- Named recipients, when the request is for specific users. Same shape as coupon_user.
CREATE TABLE IF NOT EXISTS rewards_schema.coupon_request_user (
    id UUID PRIMARY KEY NOT NULL,
    request_id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_request_user ON rewards_schema.coupon_request_user(request_id, user_id);
ALTER TABLE rewards_schema.coupon_request_user
    ADD CONSTRAINT fk_coupon_request_user_request FOREIGN KEY (request_id) REFERENCES rewards_schema.coupon_request(id);

-- --------------------------------------------------------------------------------------------
-- 2. Per-staff allowance overrides
-- --------------------------------------------------------------------------------------------
--
-- The defaults in coupon_policy apply to every support agent. This table raises (or lowers) them
-- for one person: a senior agent trusted with more, or someone new who should have less for a
-- fortnight. Absent row = the default applies, which is the common case.
--
-- Deliberately NOT a role. Roles are a small set a human can hold in their head (see
-- StaffPermission's javadoc); "senior support" as a fourth role means a new permission matrix
-- for one number. This is an exception to a default, and exceptions belong in data.
CREATE TABLE IF NOT EXISTS rewards_schema.coupon_allowance_override (
    id UUID PRIMARY KEY NOT NULL,
    staff_id UUID NOT NULL,
    staff_email VARCHAR(160),
    -- NULL on any of these means "use the platform default for this one".
    max_count_per_period INT,
    max_paise_per_period BIGINT,
    max_flat_paise BIGINT,
    max_percent_bps INT,
    -- Why this person has a different allowance. Required by the service — an unexplained
    -- exception is indistinguishable from a mistake six months later.
    reason TEXT NOT NULL,
    -- Optional expiry, so a temporary raise ("Diwali week") doesn't quietly become permanent.
    expires_at TIMESTAMPTZ,
    updated_by_staff_id UUID,
    updated_by_email VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_allowance_staff ON rewards_schema.coupon_allowance_override(staff_id);

-- --------------------------------------------------------------------------------------------
-- 3. The allowance defaults
-- --------------------------------------------------------------------------------------------
--
-- Same table and the same reasoning as V003's limits: configuration, editable by an admin
-- without a deploy, and changing it is an audited action.
--
-- ⚠️ THESE NUMBERS ARE A STARTING POINT, NOT A BUSINESS POSITION. A rolling 7-day window of
-- 10 coupons totalling ₹3,000 is deliberately tight — it is much easier to raise a limit that
-- turned out to be annoying than to explain a month of unnoticed giveaways. Decide them
-- properly before launch.
--
-- ROLLING window, not calendar. A calendar month resets on the 1st, which concentrates spend at
-- month-end and creates a "use it or lose it" incentive nobody wants in an apology budget.
INSERT INTO rewards_schema.coupon_policy (id, policy_key, policy_value, description, updated_at) VALUES
  ('01930000-0000-7000-8000-000000000105', 'support_allowance_period_days', '7',
   'Rolling window over which a support agent''s coupon allowance is measured. Rolling, not calendar — a calendar reset creates a month-end spending rush.', NOW()),
  ('01930000-0000-7000-8000-000000000106', 'support_allowance_max_count', '10',
   'How many coupons one support agent may issue per rolling period before needing admin approval. TODO(pre-launch): set deliberately.', NOW()),
  ('01930000-0000-7000-8000-000000000107', 'support_allowance_max_paise', '300000',
   'Total face value one support agent may issue per rolling period, in paise. 300000 = Rs 3,000. Percentage coupons count at their max_discount_paise cap — the worst case, since that is the exposure. TODO(pre-launch): set deliberately.', NOW()),
  ('01930000-0000-7000-8000-000000000108', 'request_auto_expire_days', '14',
   'A pending coupon request older than this is expired automatically. A goodwill gesture approved three weeks after the complaint is worse than a prompt no.', NOW())
ON CONFLICT (policy_key) DO NOTHING;

COMMENT ON TABLE rewards_schema.coupon_request IS
    'Approval workflow for coupons the requester cannot issue themselves — support above their allowance, or a salon owner (who can never issue directly, because BMP may be funding the discount).';
COMMENT ON TABLE rewards_schema.coupon_allowance_override IS
    'Per-staff exception to the default coupon allowance. Absent row = platform default.';
