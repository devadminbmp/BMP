-- V006__payment_schema.sql
-- Schema: payment_schema  |  Module: bmp-payment
-- Generated from the locked table definitions in CONTEXT.md.
-- NOTE: bmp-payment/package-info.java (existing code, written during the
-- earlier skeleton session) documents a simpler 4-table version. This
-- migration implements CONTEXT.md's later, more detailed, explicitly-locked
-- 10-table design instead, and the package-info.java doc comment has been
-- corrected to match. Flagged for founder confirmation.

CREATE SCHEMA IF NOT EXISTS payment_schema;

CREATE TABLE payment_schema.payment_order (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- real FK -> booking_schema.booking, UNIQUE
    razorpay_order_id VARCHAR(64),  -- UK; NULL until real Razorpay call wired (Phase 3)
    idempotency_key VARCHAR(80) NOT NULL,  -- booking_id + attempt_number
    amount_paise BIGINT NOT NULL,
    commission_paise BIGINT NOT NULL,  -- FROZEN at creation
    salon_share_paise BIGINT NOT NULL,  -- FROZEN at creation
    razorpay_raw_webhook JSONB,  -- full payload stored for audit/replay
    payment_captured_at TIMESTAMPTZ,  -- webhook-only, legal transaction timestamp
    status VARCHAR(20) NOT NULL,  -- created/captured/failed
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.webhook_event (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    razorpay_event_id VARCHAR(80) NOT NULL,  -- UK — dedup at DB level
    event_type VARCHAR(60) NOT NULL,  -- e.g. payment.captured, transfer.processed
    raw_payload JSONB NOT NULL,  -- store raw, return 200 immediately, process async
    processed BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.razorpay_linked_account (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- UK, logical ref -> salon_schema.salon
    razorpay_account_id VARCHAR(64),
    verification_status VARCHAR(20) NOT NULL,  -- pending/verified/failed_retrying/failed_blocked
    bookings_blocked BOOLEAN NOT NULL,  -- true after 3rd KYC failure
    bank_account_changed_at TIMESTAMPTZ,  -- 24h cooling period enforced from this
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.payout_queue_item (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- FK -> booking_schema.booking, created on booking.completed
    salon_id UUID NOT NULL,
    amount_paise BIGINT NOT NULL,
    payout_eligible_after TIMESTAMPTZ NOT NULL,  -- booking_completed_at + 7 days
    queue_status VARCHAR(20) NOT NULL,  -- pending/held_pending_kyc/eligible/included_in_batch/skipped
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.payout_batch (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- one per salon per night
    total_amount_paise BIGINT NOT NULL,
    razorpay_settlement_id VARCHAR(64),  -- bank credit proof, T+1
    retry_count SMALLINT NOT NULL,  -- max 3 auto-retries
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.commission_ledger (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    entry_type VARCHAR(30) NOT NULL,  -- commission_earned/commission_refunded/payout_transfer/adjustment
    amount_paise BIGINT NOT NULL,  -- signed: positive=credit, negative=debit
    reference_id UUID,  -- e.g. booking_id or payout_batch_id
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.refund_execution (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    refund_ticket_id UUID NOT NULL,  -- logical ref -> booking_schema.refund_ticket
    razorpay_refund_id VARCHAR(64),  -- rfnd_XXXXXXX, appears on customer bank statement
    amount_paise BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.bmp_account (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    account_type VARCHAR(20) NOT NULL,  -- operations/reserve
    balance_paise BIGINT NOT NULL,
    total_refunds_absorbed_paise BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_schema.saas_subscription (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- UK
    plan VARCHAR(10) NOT NULL,  -- pilot/standard/premium
    monthly_fee_paise BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE payment_schema.saas_invoice (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    subscription_id UUID NOT NULL,  -- FK -> saas_subscription.id
    amount_paise BIGINT NOT NULL,
    gst_paise BIGINT NOT NULL,  -- 18% GST, required for B2B invoices in India
    razorpay_payment_link_id VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Foreign keys within payment_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE payment_schema.saas_invoice ADD CONSTRAINT fk_saas_invoice_subscription_id FOREIGN KEY (subscription_id) REFERENCES payment_schema.saas_subscription(id);

-- Append-only tables: DELETE/UPDATE revoked at DB level (Schema Rule).
-- <app_role> must be replaced with the actual DB role bmp-app connects as
-- (see bmp-app/src/main/resources/application.yml spring.datasource.username).
REVOKE UPDATE, DELETE ON payment_schema.commission_ledger FROM PUBLIC;

-- Indexes on FK / lookup columns
CREATE INDEX idx_payment_order_booking_id ON payment_schema.payment_order(booking_id);
CREATE INDEX idx_payment_order_razorpay_order_id ON payment_schema.payment_order(razorpay_order_id);
CREATE INDEX idx_webhook_event_razorpay_event_id ON payment_schema.webhook_event(razorpay_event_id);
CREATE INDEX idx_razorpay_linked_account_salon_id ON payment_schema.razorpay_linked_account(salon_id);
CREATE INDEX idx_razorpay_linked_account_razorpay_account_id ON payment_schema.razorpay_linked_account(razorpay_account_id);
CREATE INDEX idx_payout_queue_item_booking_id ON payment_schema.payout_queue_item(booking_id);
CREATE INDEX idx_payout_queue_item_salon_id ON payment_schema.payout_queue_item(salon_id);
CREATE INDEX idx_payout_batch_salon_id ON payment_schema.payout_batch(salon_id);
CREATE INDEX idx_payout_batch_razorpay_settlement_id ON payment_schema.payout_batch(razorpay_settlement_id);
CREATE INDEX idx_commission_ledger_reference_id ON payment_schema.commission_ledger(reference_id);
CREATE INDEX idx_refund_execution_refund_ticket_id ON payment_schema.refund_execution(refund_ticket_id);
CREATE INDEX idx_refund_execution_razorpay_refund_id ON payment_schema.refund_execution(razorpay_refund_id);
CREATE INDEX idx_saas_subscription_salon_id ON payment_schema.saas_subscription(salon_id);
CREATE INDEX idx_saas_invoice_subscription_id ON payment_schema.saas_invoice(subscription_id);
CREATE INDEX idx_saas_invoice_razorpay_payment_link_id ON payment_schema.saas_invoice(razorpay_payment_link_id);
