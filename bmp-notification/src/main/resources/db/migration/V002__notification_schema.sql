-- V009__notification_schema.sql
-- Schema: notification_schema  |  Module: bmp-notification
-- Generated from the locked table definitions in CONTEXT.md.

CREATE SCHEMA IF NOT EXISTS notification_schema;

CREATE TABLE notification_schema.notification_log (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    recipient_user_id UUID NOT NULL,  -- logical ref -> user_schema.users
    channel VARCHAR(10) NOT NULL,  -- whatsapp/sms/push/email
    template_code VARCHAR(60) NOT NULL,  -- e.g. OTP_LOGIN, BOOKING_CONFIRMED
    payload JSONB NOT NULL,  -- rendered template variables
    status VARCHAR(10) NOT NULL,  -- queued/sent/delivered/failed
    provider_message_id VARCHAR(120),
    error_reason TEXT,
    outbox_entry_id UUID,  -- traces back to common_schema.outbox row
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ
);

-- Indexes on FK / lookup columns
CREATE INDEX idx_notification_log_recipient_user_id ON notification_schema.notification_log(recipient_user_id);
CREATE INDEX idx_notification_log_provider_message_id ON notification_schema.notification_log(provider_message_id);
CREATE INDEX idx_notification_log_outbox_entry_id ON notification_schema.notification_log(outbox_entry_id);
