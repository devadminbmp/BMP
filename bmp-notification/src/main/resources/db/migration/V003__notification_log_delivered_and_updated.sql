-- V003__notification_log_delivered_and_updated.sql
-- Adds delivered_at + updated_at to notification_schema.notification_log.
-- Ported from Shivam's Session 7 notification-CRUD work (BMP-6/BMP-30), which built a
-- richer REST API (pagination, stats, pending-queue, delivered transition) against these
-- two columns — but that work landed on a stale pre-flatten package layout whose entity
-- also had incorrect column names (recipient_id/error_message instead of the locked
-- recipient_user_id/error_reason from V002). This migration adds the two genuinely new
-- columns his work needed; the flat NotificationLog entity keeps the V002 column names.

ALTER TABLE notification_schema.notification_log
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ;
