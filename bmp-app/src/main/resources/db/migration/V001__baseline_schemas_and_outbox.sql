-- BMP baseline: one database, one schema per module (created by Flyway config).
-- Cross-schema foreign keys are REAL (pivot decision T3).
-- Money columns are BIGINT paise. All PKs are UUID (v7 generated in app).

CREATE EXTENSION IF NOT EXISTS postgis;

-- ── The outbox: the Kafka replacement (pivot decision T2) ──────────────
CREATE TABLE common_schema.outbox (
    id            UUID PRIMARY KEY,
    event_type    VARCHAR(100) NOT NULL,
    aggregate_id  UUID         NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    processed     BOOLEAN      NOT NULL DEFAULT FALSE,
    processed_at  TIMESTAMPTZ,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(500)
);
CREATE INDEX idx_outbox_unprocessed ON common_schema.outbox (processed, created_at)
    WHERE processed = FALSE;

-- ── NEXT MIGRATIONS (in this order, per the master plan) ────────────────
-- V002  user_schema      : users, user_roles, otp_requests, refresh_tokens, onboarding_state
-- V003  salon_schema     : salon (PostGIS), salon_policy, salon_service, stylist, stylist_salon, salon_staff, staff_invites
-- V004  salon_schema     : ⚠️ AVAILABILITY MODEL — do NOT write this before the
--                          paper design against 3 real salons is approved (T4)
-- V005  booking_schema   : booking, booking_service_item, booking_events, slot_lock
--                          (real FKs to salon_schema.salon, user_schema.users)
-- V006  payment_schema   : payment, payout_record, payout_queue, bank_account
--                          (real FK to booking_schema.booking)
-- V007  review_schema, rewards_schema
-- V008  admin_schema     : bmp_staff, support_ticket, support_message,
--                          audit_log (+ REVOKE UPDATE, DELETE — append-only at DB level)
