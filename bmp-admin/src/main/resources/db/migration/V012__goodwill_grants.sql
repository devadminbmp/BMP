-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- V012 — goodwill_grant: what has already been handed back on a booking, other than refunds.
-- Session 59.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- THE HOLE THIS CLOSES
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- Darshan's rule is that nobody gives back more than the customer paid. V010/V011 implemented it in
-- GoodwillCapService as:
--
--     remaining = booking.final_amount - booking.total_refunded
--
-- which is right for refunds and blind to everything else. A ₹600 booking could receive a ₹500
-- coupon and then a second ₹500 coupon: each one passes, because a coupon never touches
-- `total_refunded`. The rule held against one kind of goodwill and not the others — which is the
-- worse failure, because the cap LOOKS enforced.
--
-- WHY A TABLE AND NOT A QUERY OVER approval_request
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- Summing approval_request rows for the booking would miss precisely the cases that matter most:
-- goodwill issued WITHIN somebody's own authority never creates an approval request at all. An
-- agent with a ₹500 band could issue ₹500 four times and no approval row would exist for any of
-- them. The ledger has to be written by the act of granting, not by the act of approving.
--
-- WHY REFUNDS ARE DELIBERATELY EXCLUDED
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- A refund already increments `booking.total_refunded` in bmp-booking, which is the authoritative
-- record and the one the customer's statement agrees with. Recording it here too would double-count
-- it and halve the real ceiling — a customer refunded ₹300 on a ₹600 booking would appear to have
-- had ₹600 back.
--
-- That invariant is a CHECK constraint rather than a comment, because "remember not to insert
-- refunds here" is exactly the kind of instruction that survives about six months.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS admin_schema.goodwill_grant (
    id UUID PRIMARY KEY NOT NULL,

    -- Not a foreign key: bookings live in bmp-booking's schema, in another service. Referential
    -- integrity across a service boundary is enforced by the code that writes here, which reads the
    -- booking first (GoodwillCapService) and refuses if it does not exist.
    booking_id UUID NOT NULL,

    action_type VARCHAR(60) NOT NULL,
    value_paise BIGINT NOT NULL,

    -- Null when the grant was within the actor's own authority and needed nobody's signature. That
    -- is the common case, and the reason this table exists.
    approval_request_id UUID,

    granted_by_staff_id UUID NOT NULL,
    granted_by_role VARCHAR(30) NOT NULL,

    -- The coupon code, wallet transaction id, or whatever the executor produced. For tracing a row
    -- here back to the thing the customer actually received.
    reference VARCHAR(120),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A grant of zero or less is not goodwill, it is a bug that would silently pass every cap check.
ALTER TABLE admin_schema.goodwill_grant
    DROP CONSTRAINT IF EXISTS chk_goodwill_grant_positive;
ALTER TABLE admin_schema.goodwill_grant
    ADD CONSTRAINT chk_goodwill_grant_positive CHECK (value_paise > 0);

-- The double-counting guard, in the database. See the header.
ALTER TABLE admin_schema.goodwill_grant
    DROP CONSTRAINT IF EXISTS chk_goodwill_grant_not_refund;
ALTER TABLE admin_schema.goodwill_grant
    ADD CONSTRAINT chk_goodwill_grant_not_refund CHECK (action_type <> 'refund.issue');

/*
 * Idempotency for the approved path.
 *
 * ApprovalRequestService marks a request FAILED and keeps the approval so it can be retried without
 * being re-approved. A retry that succeeds must not add a second grant row — otherwise a single
 * ₹400 coupon that failed once would consume ₹800 of the customer's ceiling, and the person chasing
 * it would be refused for reasons nobody could see.
 *
 * Partial, because the null case (within-authority grants) is the common one and each is genuinely
 * distinct.
 */
CREATE UNIQUE INDEX IF NOT EXISTS uq_goodwill_grant_approval
    ON admin_schema.goodwill_grant (approval_request_id)
    WHERE approval_request_id IS NOT NULL;

-- The only read pattern: "what has already gone out on this booking", on every capped action.
CREATE INDEX IF NOT EXISTS idx_goodwill_grant_booking
    ON admin_schema.goodwill_grant (booking_id);

COMMENT ON TABLE admin_schema.goodwill_grant IS
    'Non-refund goodwill already given against a booking. Refunds live on the booking itself in '
    'bmp-booking and are excluded here by CHECK constraint to avoid double-counting. Read by '
    'GoodwillCapService to enforce "never give back more than the customer paid".';
