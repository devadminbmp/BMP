-- V005 — refund requests.
--
-- ============================================================================================
-- WHY THIS LIVES IN admin_schema AND NOT IN A PAYMENTS SERVICE
-- ============================================================================================
-- A refund is two separate things, and conflating them is why refund handling goes wrong:
--
--   1. The DECISION — a customer asked, an agent agreed, someone approved the amount. That is
--      support workflow, it happens in the console, and it needs recording whether or not any
--      money ever moves.
--   2. The PAYOUT — actually returning funds through a payment provider. That belongs to
--      bmp-payment, which does not exist yet.
--
-- This table is (1). When bmp-payment arrives it consumes approved rows and records the payout
-- against them; nothing here needs restructuring.
--
-- Building it now rather than later is deliberate: support is already fielding "I want my money
-- back" and the alternative to a queue is a spreadsheet. Improvised money handling is how you
-- end up paying twice, or not at all, with no way to tell which.
--
-- ⚠️ Nothing can be PAID until payments exist. The service refuses to move a request past
-- 'approved', and the console says so at the top of the screen rather than letting an agent
-- promise a customer money that will never arrive.
-- ============================================================================================

CREATE TABLE IF NOT EXISTS admin_schema.refund_request (
    id UUID PRIMARY KEY NOT NULL,
    booking_id UUID NOT NULL,                  -- logical ref -> booking_schema.booking
    booking_ref VARCHAR(20),                   -- denormalised: the console shows it constantly
    customer_user_id UUID,                     -- logical ref -> user_schema.users
    salon_id UUID,

    -- Integer paise, like all money in this system.
    amount_paise BIGINT NOT NULL,
    -- What the booking was worth, so an agent can see at a glance whether this is a full or
    -- partial refund without a second lookup.
    booking_amount_paise BIGINT NOT NULL DEFAULT 0,

    reason TEXT NOT NULL,

    -- requested | approved | rejected | paid | blocked
    --
    -- 'blocked' is the honest state for "we agreed, but there is no payment system to return
    -- money through". It is NOT a rejection — the customer's claim stands — and separating the
    -- two means nobody later mistakes a technical limitation for a refusal.
    status VARCHAR(20) NOT NULL,
    blocked_reason TEXT,

    requested_by UUID NOT NULL,                -- logical ref -> bmp_staff.id
    requested_by_email VARCHAR(160),           -- denormalised, stays readable after they leave
    -- Approval is a SECOND person by policy (see RefundService). Recording who, separately from
    -- who requested it, is the whole point of having the two columns.
    decided_by UUID,
    decided_by_email VARCHAR(160),
    decided_at TIMESTAMPTZ,
    decision_note TEXT,

    -- Set by bmp-payment when it exists. Null forever until then.
    paid_at TIMESTAMPTZ,
    payment_reference VARCHAR(120),

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refund_request_status
    ON admin_schema.refund_request(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_refund_request_booking
    ON admin_schema.refund_request(booking_id);

-- One open request per booking. Without this, a customer who asks twice — or two agents working
-- the same complaint — produces two live requests, and the day payments land, both pay out.
CREATE UNIQUE INDEX IF NOT EXISTS uk_refund_request_open_booking
    ON admin_schema.refund_request(booking_id)
    WHERE status IN ('requested', 'approved', 'blocked');
