-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V008 — invoices and receipts. Session 49.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── ONE DOCUMENT, TWO STATES ───────────────────────────────────────────────────────────────────
-- An invoice is raised when a booking is confirmed and reads "amount due". When the money is
-- recorded it becomes a receipt — same row, same number, now carrying how and when it was paid.
--
-- Not two tables and not two numbers, because they are the same commercial document at two points
-- in its life. A customer who is asked for BMP-INV-000412 and finds a *receipt* numbered
-- differently has to be talked through it by somebody.
--
-- ── WHY THE LINE ITEMS ARE COPIED, NOT JOINED ──────────────────────────────────────────────────
-- booking_service_item already holds the services and prices, and it would be tempting to render
-- the invoice from it on every read. That is wrong for a financial document:
--
--   • A salon renaming "Haircut" to "Classic Cut" would silently rewrite last month's invoices.
--   • Removing a service would leave an invoice with a dangling line.
--   • A reschedule or a partial refund changes the booking; the invoice as ISSUED must not move.
--
-- The same reasoning already applied once in this codebase: booking_service_item itself snapshots
-- name_snapshot and price_paise_snapshot instead of pointing at salon_service. This is that rule
-- applied one level up. An invoice is a statement about a moment.
--
-- ── MONEY ──────────────────────────────────────────────────────────────────────────────────────
-- Integer paise throughout, never a float, matching every other money column in this schema.

CREATE SEQUENCE IF NOT EXISTS booking_schema.invoice_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS booking_schema.invoice (
    id UUID PRIMARY KEY NOT NULL,

    -- One invoice per booking. A second one for the same booking would mean two documents
    -- claiming the same money, which is the beginning of every reconciliation nightmare.
    booking_id UUID NOT NULL UNIQUE,
    salon_id UUID NOT NULL,
    customer_id UUID,

    -- BMP-INV-000412. From a SEQUENCE, never count(*)+1 — Session 48 fixed exactly that bug on
    -- support ticket references. Two invoices raised in the same second must not collide, and a
    -- deleted row must not cause a number to be reused.
    invoice_no VARCHAR(24) NOT NULL UNIQUE,

    -- Frozen at issue. See the note above on why these are copied rather than joined.
    salon_name VARCHAR(160) NOT NULL,
    salon_address VARCHAR(500),
    customer_name VARCHAR(160),
    booking_ref VARCHAR(32),

    gross_paise BIGINT NOT NULL,
    discount_paise BIGINT NOT NULL DEFAULT 0,
    total_paise BIGINT NOT NULL,

    -- due / paid / refunded / cancelled
    status VARCHAR(12) NOT NULL DEFAULT 'due',

    -- Filled when the money is recorded. `paid_method` is free text ('cash', 'upi', 'card',
    -- 'razorpay') because how a Bengaluru salon takes money is not something to enumerate now.
    paid_at TIMESTAMPTZ,
    paid_method VARCHAR(20),
    paid_reference VARCHAR(120),
    -- WHO recorded it. A cash payment marked by a staff member is a claim by a person, and the
    -- first question when the till doesn't balance is who said it was paid.
    recorded_by_user_id UUID,

    refunded_paise BIGINT NOT NULL DEFAULT 0,

    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS booking_schema.invoice_line (
    id UUID PRIMARY KEY NOT NULL,
    invoice_id UUID NOT NULL,
    -- Position on the printed document. Without it the lines come back in whatever order the
    -- rows happen to sit in, and an invoice whose lines reorder between two views looks forged.
    line_no INT NOT NULL,
    description VARCHAR(200) NOT NULL,
    stylist_name VARCHAR(120),
    duration_minutes INT,
    amount_paise BIGINT NOT NULL
);

ALTER TABLE booking_schema.invoice_line
    ADD CONSTRAINT fk_invoice_line_invoice FOREIGN KEY (invoice_id)
    REFERENCES booking_schema.invoice(id) ON DELETE CASCADE;

ALTER TABLE booking_schema.invoice
    ADD CONSTRAINT chk_invoice_status CHECK (status IN ('due', 'paid', 'refunded', 'cancelled'));

-- Money is never negative, and the total must be what the arithmetic says. A document that
-- doesn't add up is worse than no document — somebody will act on it.
ALTER TABLE booking_schema.invoice
    ADD CONSTRAINT chk_invoice_amounts
    CHECK (gross_paise >= 0 AND discount_paise >= 0 AND total_paise >= 0
           AND refunded_paise >= 0
           AND total_paise = gross_paise - discount_paise
           AND discount_paise <= gross_paise
           AND refunded_paise <= total_paise);

-- 'paid' must say when. Written explicitly for both directions, because the tempting
-- `(status <> 'paid')` form is vacuous — that mistake was made and caught in V023 this session.
ALTER TABLE booking_schema.invoice
    ADD CONSTRAINT chk_invoice_paid
    CHECK ((status = 'paid' AND paid_at IS NOT NULL)
        OR (status IN ('due', 'cancelled') AND paid_at IS NULL)
        OR (status = 'refunded' AND paid_at IS NOT NULL));

ALTER TABLE booking_schema.invoice_line
    ADD CONSTRAINT chk_invoice_line_amount CHECK (amount_paise >= 0);

CREATE INDEX IF NOT EXISTS idx_invoice_salon ON booking_schema.invoice (salon_id, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_invoice_customer ON booking_schema.invoice (customer_id, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_invoice_line_invoice ON booking_schema.invoice_line (invoice_id, line_no);

COMMENT ON TABLE booking_schema.invoice IS
    'One commercial document per booking. Reads "amount due" until the money is recorded, then '
    'becomes a receipt with the same number. Line items are COPIED at issue so renaming a service '
    'never rewrites last month''s invoices.';
