-- V001__auth_tables.sql  (bmp-auth-service's own migration — first one in this service)
-- Schema: user_schema (shared physical schema with bmp-user-service, per the original
-- "one Postgres, schema per module" design — auth and user are split at the SERVICE/code
-- level in Session 5, not at the schema level, since these tables were already designed
-- together and splitting the schema itself wasn't part of what was asked).
--
-- otp_requests and refresh_tokens were originally created as part of bmp-user-service's
-- V002__user_schema.sql (Session 4). Moved into bmp-auth-service's own migration here
-- since bmp-auth now owns login end-to-end. If both services' Flyway ever run against the
-- same schema in the same environment, coordinate so these two CREATE TABLEs aren't both
-- attempted — only bmp-auth-service should run this one.

CREATE SCHEMA IF NOT EXISTS user_schema;

CREATE TABLE user_schema.otp_requests (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    phone VARCHAR(20) NOT NULL,  -- NO FK to users — sent before user row may exist
    otp_hash VARCHAR(255) NOT NULL,  -- bcrypt, never plaintext
    attempts SMALLINT NOT NULL,
    locked_until TIMESTAMPTZ,  -- set after 3 failures, 10-min lockout
    expires_at TIMESTAMPTZ NOT NULL,  -- created_at + 5 minutes
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_schema.refresh_tokens (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID NOT NULL,  -- logical ref to user_schema.users.id (owned by bmp-user-service)
    selector VARCHAR(32) UNIQUE NOT NULL,  -- random, stored PLAIN, indexed lookup key (split-token pattern)
    token_hash VARCHAR(255) NOT NULL,  -- bcrypt hash of the verifier half only, never plaintext
    device_fingerprint VARCHAR(255),  -- fraud detection, referral gaming
    revoked BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_refresh_tokens_selector ON user_schema.refresh_tokens(selector);

CREATE INDEX idx_otp_requests_phone ON user_schema.otp_requests(phone);
CREATE INDEX idx_refresh_tokens_user_id ON user_schema.refresh_tokens(user_id);
