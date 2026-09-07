-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V004 — what payment_order needs before it can carry real money. Session 50.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── 1. salon_id: you cannot pay out a salon you cannot identify ────────────────────────────────
-- The table splits every payment into commission_paise and salon_share_paise and then has no
-- column saying WHOSE share that is. Answering "what do we owe Lumière this week?" meant joining
-- back to booking_schema across a service boundary, which the architecture rule forbids
-- (cross-schema FKs are logical only, and cross-SERVICE joins are not a thing at all).
--
-- Nullable, because rows written before this migration genuinely don't know. Backfilling from
-- booking_schema would be exactly the cross-service reach this column exists to remove, and there
-- are no real payments yet, so there is nothing of value to lose.
--
-- ── 2. commission_bps: the RATE, frozen alongside the amount ───────────────────────────────────
-- commission_paise is already frozen at creation. The rate that produced it was not recorded, so
-- a past split could not be explained — only recomputed against today's rate, which is a
-- different number the moment anyone renegotiates.
--
-- "12% of ₹800 is ₹96" is a claim a salon may dispute two months later. Storing 1200 next to the
-- 9600 makes the arithmetic checkable forever.
--
-- ── 3. THE BUG THIS FIXES ──────────────────────────────────────────────────────────────────────
-- PaymentOrderService had `private static final int COMMISSION_BPS = 1200;` and used it for every
-- order. Meanwhile Session 48 gave each salon its own commission_bps on salon_policy, set by an
-- admin at approval, explicitly so rates could be negotiated per partner — and made it
-- unsettable by the owner because it is a money field.
--
-- So the platform could agree 8% with a salon, store 8% in salon_policy, show 8% in the admin
-- console, and then charge 12% on every booking. Nobody would notice until a partner audited a
-- statement. The rate now comes from the salon and is written here.
--
-- ── 4. gateway columns ─────────────────────────────────────────────────────────────────────────
-- razorpay_order_id exists; the PAYMENT id (pay_XXXX) does not, and that is the reference that
-- appears on the customer's bank statement and the one support is quoted down the phone.

ALTER TABLE payment_schema.payment_order
    ADD COLUMN IF NOT EXISTS salon_id UUID;

ALTER TABLE payment_schema.payment_order
    ADD COLUMN IF NOT EXISTS commission_bps INT;

-- pay_XXXXXXXXXXXX. Distinct from razorpay_order_id (order_XXXX): one order can have several
-- payment attempts, and only the captured one has an id worth keeping.
ALTER TABLE payment_schema.payment_order
    ADD COLUMN IF NOT EXISTS gateway_payment_id VARCHAR(64);

-- Why a payment failed, verbatim from the gateway. Kept because "it didn't work" is the single
-- most common support contact and the answer is otherwise unrecoverable.
ALTER TABLE payment_schema.payment_order
    ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(300);

-- The money identity, asserted by the database.
--
-- commission + salon_share must equal the amount. If they don't, somebody is owed money nobody
-- can account for — and the failure is silent, because both numbers look individually plausible.
-- NOT VALID so the constraint applies to new rows without failing the migration on any older row
-- written before it; validate separately once the existing rows are known good.
ALTER TABLE payment_schema.payment_order
    ADD CONSTRAINT chk_payment_split
    CHECK (commission_paise + salon_share_paise = amount_paise
           AND commission_paise >= 0 AND salon_share_paise >= 0 AND amount_paise >= 0)
    NOT VALID;

-- 0–50%. Zero is legitimate (a launch partner paying nothing); 5000 catches the predictable
-- mistake of typing "12" meaning 12% into a basis-points field, which would be 0.12%.
ALTER TABLE payment_schema.payment_order
    ADD CONSTRAINT chk_payment_commission_bps
    CHECK (commission_bps IS NULL OR commission_bps BETWEEN 0 AND 5000)
    NOT VALID;

-- "What do we owe this salon?" — the payout query. Partial: only captured money is payable.
CREATE INDEX IF NOT EXISTS idx_payment_order_salon_captured
    ON payment_schema.payment_order (salon_id, payment_captured_at)
    WHERE status = 'captured';

-- The webhook's lookup: find the order this gateway event refers to.
CREATE INDEX IF NOT EXISTS idx_payment_order_gateway_order
    ON payment_schema.payment_order (razorpay_order_id)
    WHERE razorpay_order_id IS NOT NULL;

COMMENT ON COLUMN payment_schema.payment_order.commission_bps IS
    'The RATE that produced commission_paise, frozen at creation. Comes from the salon''s own '
    'salon_policy.commission_bps — NOT a platform constant. See V004.';

COMMENT ON COLUMN payment_schema.payment_order.salon_id IS
    'Whose salon_share_paise this is. Without it, payouts require a cross-service join.';
