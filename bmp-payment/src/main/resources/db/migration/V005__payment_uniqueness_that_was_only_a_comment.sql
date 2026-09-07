-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V005 — the payment schema's uniqueness was a comment, not a constraint. Session 50.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── WHAT WAS FOUND ─────────────────────────────────────────────────────────────────────────────
-- V002 documents four columns as unique:
--
--   payment_order.booking_id          "real FK -> booking_schema.booking, UNIQUE"
--   payment_order.razorpay_order_id   "UK; NULL until real Razorpay call wired"
--   payment_order.idempotency_key     "booking_id + attempt_number"
--   webhook_event.razorpay_event_id   "UK — dedup at DB level"
--
-- Not one of them had a unique index. The only unique indexes in the entire schema were the ten
-- primary keys. `webhook_event` even got a `CREATE INDEX` — a plain one — on the exact column
-- whose comment says "dedup at DB level".
--
-- Found by a test that inserted the same razorpay_event_id twice and expected the second to fail.
-- It succeeded.
--
-- ── WHY THIS IS THE MOST DANGEROUS KIND OF BUG ─────────────────────────────────────────────────
-- A comment claiming a guarantee is worse than no comment, because code gets written that relies
-- on it. WebhookService is built around the assumption that a concurrent duplicate delivery hits
-- a constraint:
--
--     "The fast path for the common case. It is NOT the guarantee — two deliveries arriving
--      concurrently both pass this check. The UNIQUE index on razorpay_event_id is what actually
--      prevents double-application."
--
-- That comment described an index that did not exist. Under concurrent redelivery — which is
-- normal, gateways deliver at least once — both requests would have passed the exists() check,
-- both would have captured, and the commission ledger would have been credited TWICE for one
-- payment. Silently, and in a table whose whole purpose is to be trustworthy.
--
-- ── WHY EACH ONE MATTERS ───────────────────────────────────────────────────────────────────────
-- • webhook_event.razorpay_event_id — double-application of a capture. Ledger wrong, customer
--   possibly emailed two receipts.
-- • payment_order.idempotency_key   — a retried booking opens a SECOND payment order. The
--   customer ends up holding two payment links for one appointment and can pay both.
-- • payment_order.booking_id        — same outcome, reached differently.
-- • payment_order.razorpay_order_id — the webhook looks an order up by this id. Two rows means
--   findByRazorpayOrderId picks one arbitrarily, and the capture lands on whichever the database
--   happened to return.
--
-- ── PARTIAL WHERE NULLABLE ─────────────────────────────────────────────────────────────────────
-- razorpay_order_id is null until an order is opened at the gateway. Postgres already allows many
-- NULLs in a unique index, but the partial form states the intent and keeps the index small.

CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_event_gateway_id
    ON payment_schema.webhook_event (razorpay_event_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_order_idempotency_key
    ON payment_schema.payment_order (idempotency_key);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_order_booking
    ON payment_schema.payment_order (booking_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_order_gateway_order_id
    ON payment_schema.payment_order (razorpay_order_id)
    WHERE razorpay_order_id IS NOT NULL;

-- The plain index V002 created on the same column. Redundant now: the unique index above serves
-- every lookup it served. Dropped rather than left, because two indexes on one column is a thing
-- the next reader has to stop and think about.
DROP INDEX IF EXISTS payment_schema.idx_webhook_event_razorpay_event_id;

COMMENT ON INDEX payment_schema.uq_webhook_event_gateway_id IS
    'THE dedup guarantee for webhook redelivery. WebhookService relies on this insert failing '
    'under concurrent delivery — its exists() check is only the fast path. V002 documented this '
    'as a UK and created a non-unique index instead; see V005.';
