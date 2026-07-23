-- V003__otp_email_and_oauth.sql — Session 6: dual-channel OTP (email + phone) + Google
-- OAuth2 signup for customers. Neither existed in the original locked schema (CONTEXT.md
-- predates this ticket) — new table/column, not a change to a locked one.

-- otp_requests.email: nullable, since a LOGIN (existing user) doesn't need the caller to
-- resupply an email — bmp-auth looks the stored one up from bmp-user-service instead. Only
-- SIGNUP (brand-new phone) requires the request to carry one, enforced in AuthService, not
-- the DB, since "required sometimes" isn't expressible as a plain NOT NULL here.
ALTER TABLE user_schema.otp_requests ADD COLUMN email VARCHAR(160);

-- One row per (provider, provider_subject) — the Google account's stable "sub" claim.
-- user_id is a logical ref to user_schema.users, same cross-schema convention as everywhere
-- else in this repo (no physical FK across service schemas).
CREATE TABLE user_schema.oauth_identity (
    id UUID PRIMARY KEY NOT NULL,           -- PK, UUIDv7
    user_id UUID NOT NULL,                  -- logical ref -> user_schema.users.id
    provider VARCHAR(20) NOT NULL,          -- 'google' for now; column exists for whatever's next
    provider_subject VARCHAR(255) NOT NULL, -- Google's `sub` claim — stable, unlike email
    email VARCHAR(160),                     -- snapshot at link time, for support/debugging only
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_oauth_identity_provider_subject ON user_schema.oauth_identity(provider, provider_subject);
CREATE INDEX idx_oauth_identity_user_id ON user_schema.oauth_identity(user_id);
