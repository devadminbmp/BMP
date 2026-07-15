-- V001__common_outbox.sql
-- Copied into EVERY service's own migration folder (Session 5 microservices pivot).
-- common_schema.outbox is used by every service via the shared bmp-common library
-- (OutboxPublisher/OutboxEntry) — whichever service starts first in a fresh environment
-- creates it; IF NOT EXISTS makes this safe regardless of startup order, since each
-- service's Flyway history is tracked independently and doesn't know about the others.

CREATE SCHEMA IF NOT EXISTS common_schema;

CREATE TABLE IF NOT EXISTS common_schema.outbox (
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
CREATE INDEX IF NOT EXISTS idx_outbox_unprocessed ON common_schema.outbox (processed, created_at)
    WHERE processed = FALSE;
