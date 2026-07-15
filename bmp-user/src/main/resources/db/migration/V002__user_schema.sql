-- V002__user_schema.sql  (bmp-user-service's own migration)
-- Schema: user_schema  |  Module: bmp-user-service
-- Session 5 note: otp_requests and refresh_tokens were REMOVED from this file — they
-- moved to bmp-auth-service's own migration (V001__auth_tables.sql) since bmp-auth now
-- owns login end-to-end. Both still physically live in user_schema on the shared
-- Postgres; only the OWNING SERVICE/CODE changed, not the schema name. Do not recreate
-- them here — bmp-auth-service's Flyway is responsible for them.

CREATE SCHEMA IF NOT EXISTS user_schema;

CREATE TABLE user_schema.users (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    phone VARCHAR(20) NOT NULL,  -- UK, E.164 identity key
    name VARCHAR(120),
    gender VARCHAR(10),  -- male/female/other
    age SMALLINT,
    email VARCHAR(160),
    profile_photo_url VARCHAR(500),
    hair_type VARCHAR(30),
    hair_length VARCHAR(30),
    default_role VARCHAR(20) NOT NULL,  -- customer/stylist/salon_owner/manager
    is_verified BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_schema.user_roles (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID NOT NULL,  -- FK -> users.id
    role VARCHAR(20) NOT NULL,  -- customer/stylist/salon_owner/manager
    salon_id UUID,  -- NULL for customer role, logical ref to salon_schema.salon
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_schema.onboarding_state (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID NOT NULL,  -- FK -> users.id
    state_json JSONB NOT NULL,  -- transient crash-recovery payload
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Foreign keys within user_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE user_schema.user_roles ADD CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES user_schema.users(id);
ALTER TABLE user_schema.onboarding_state ADD CONSTRAINT fk_onboarding_state_user_id FOREIGN KEY (user_id) REFERENCES user_schema.users(id);

-- Indexes on FK / lookup columns
CREATE INDEX idx_user_roles_user_id ON user_schema.user_roles(user_id);
CREATE INDEX idx_user_roles_salon_id ON user_schema.user_roles(salon_id);
CREATE INDEX idx_onboarding_state_user_id ON user_schema.onboarding_state(user_id);
