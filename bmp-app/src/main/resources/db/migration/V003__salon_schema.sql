-- V003__salon_schema.sql
-- Schema: salon_schema  |  Module: bmp-salon
-- Generated from the locked table definitions in CONTEXT.md.
-- EXCLUDES stylist_availability (and any walk-in-block table) — those are V004,
-- a SEPARATE migration (see V004__availability_model.sql), gated on the paper
-- design + founder approval (see AvailabilityApi.java javadoc).
-- NOTE: salon.location is GEOGRAPHY(POINT) (PostGIS) — the entity maps it as a
-- placeholder String (WKT) for now; swap for a real Point/JTS mapping when the
-- proximity-search ticket is built (see ST_DWithin usage in CONTEXT.md).

CREATE SCHEMA IF NOT EXISTS salon_schema;

CREATE TABLE salon_schema.salon (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    name VARCHAR(160) NOT NULL,
    location GEOGRAPHY(POINT),  -- PostGIS point; mapped as WKT string at the JPA layer, see note in entity
    status VARCHAR(20) NOT NULL,  -- pending/approved/rejected/suspended/closed
    stylist_assignment_strategy VARCHAR(20) NOT NULL,  -- random/highest_rated/least_loaded
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE salon_schema.salon_policy (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- FK -> salon.id
    template VARCHAR(20) NOT NULL,  -- strict/standard/flexible
    free_cancel_hours INT NOT NULL,
    late_grace_minutes INT NOT NULL,
    require_prepayment BOOLEAN NOT NULL,  -- always true in Phase 1
    slot_granularity_minutes INT DEFAULT 15 NOT NULL,  -- per-salon override, locked in availability paper design Q1
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE salon_schema.salon_hours (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- FK -> salon.id
    day_of_week SMALLINT NOT NULL,  -- 0-6
    open_time TIME NOT NULL,
    close_time TIME NOT NULL  -- slots must END by this time
);

CREATE TABLE salon_schema.salon_service (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- FK -> salon.id
    name VARCHAR(160) NOT NULL,
    price_paise BIGINT NOT NULL,
    duration_minutes INT NOT NULL,  -- shown to customer
    requires_stylist_assignment BOOLEAN NOT NULL,  -- false = walk-in pool
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE salon_schema.stylist (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID,  -- nullable — quick-add before stylist claims profile
    name VARCHAR(120) NOT NULL,
    overall_rating NUMERIC(3,2),  -- cross-salon lifetime
    total_reviews INT NOT NULL,
    is_top_stylist BOOLEAN NOT NULL,  -- total_reviews>=50 AND overall_rating>=4.7
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE salon_schema.stylist_salon (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    stylist_id UUID NOT NULL,  -- FK -> stylist.id
    salon_id UUID NOT NULL,  -- FK -> salon.id
    status VARCHAR(10) NOT NULL,  -- active/alumni — NEVER deleted
    salon_rating NUMERIC(3,2),  -- FROZEN when alumni
    salon_review_count INT NOT NULL,  -- FROZEN when alumni
    is_available_today BOOLEAN NOT NULL,  -- fastest availability check, flipped in one tap
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ
);

CREATE TABLE salon_schema.stylist_service (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    stylist_id UUID NOT NULL,  -- FK -> stylist.id
    salon_id UUID NOT NULL,  -- FK -> salon.id
    service_id UUID NOT NULL,  -- FK -> salon_service.id
    actual_duration_minutes INT NOT NULL,  -- real speed, NEVER shown to customer
    override_price_paise BIGINT  -- per-stylist pricing override
);

CREATE TABLE salon_schema.salon_combo (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- FK -> salon.id
    name VARCHAR(160) NOT NULL,
    price_paise BIGINT NOT NULL,  -- bundled discounted price
    allows_addons BOOLEAN NOT NULL,  -- customer can add services on top
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE salon_schema.salon_combo_item (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    combo_id UUID NOT NULL,  -- FK -> salon_combo.id
    service_id UUID NOT NULL,  -- FK -> salon_service.id
    requires_specialist BOOLEAN NOT NULL,  -- drives multi-stylist assignment
    sequence SMALLINT NOT NULL  -- order of services
);

CREATE TABLE salon_schema.salon_staff (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- FK -> salon.id
    user_id UUID NOT NULL,  -- logical ref to user_schema.users
    role VARCHAR(20) NOT NULL,  -- manager/etc — dashboard access level
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE salon_schema.staff_invites (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- FK -> salon.id
    phone VARCHAR(20) NOT NULL,  -- invitee, WhatsApp deep link target
    token VARCHAR(64) NOT NULL,  -- one-time invite token
    status VARCHAR(10) NOT NULL,  -- pending/accepted/declined/expired
    expires_at TIMESTAMPTZ NOT NULL,  -- 48h expiry
    created_at TIMESTAMPTZ NOT NULL
);

-- Foreign keys within salon_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE salon_schema.salon_policy ADD CONSTRAINT fk_salon_policy_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.salon_hours ADD CONSTRAINT fk_salon_hours_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.salon_service ADD CONSTRAINT fk_salon_service_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.stylist_salon ADD CONSTRAINT fk_stylist_salon_stylist_id FOREIGN KEY (stylist_id) REFERENCES salon_schema.stylist(id);
ALTER TABLE salon_schema.stylist_salon ADD CONSTRAINT fk_stylist_salon_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.stylist_service ADD CONSTRAINT fk_stylist_service_stylist_id FOREIGN KEY (stylist_id) REFERENCES salon_schema.stylist(id);
ALTER TABLE salon_schema.stylist_service ADD CONSTRAINT fk_stylist_service_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.stylist_service ADD CONSTRAINT fk_stylist_service_service_id FOREIGN KEY (service_id) REFERENCES salon_schema.salon_service(id);
ALTER TABLE salon_schema.salon_combo ADD CONSTRAINT fk_salon_combo_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.salon_combo_item ADD CONSTRAINT fk_salon_combo_item_combo_id FOREIGN KEY (combo_id) REFERENCES salon_schema.salon_combo(id);
ALTER TABLE salon_schema.salon_combo_item ADD CONSTRAINT fk_salon_combo_item_service_id FOREIGN KEY (service_id) REFERENCES salon_schema.salon_service(id);
ALTER TABLE salon_schema.salon_staff ADD CONSTRAINT fk_salon_staff_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.staff_invites ADD CONSTRAINT fk_staff_invites_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);

-- Indexes on FK / lookup columns
CREATE INDEX idx_salon_policy_salon_id ON salon_schema.salon_policy(salon_id);
CREATE INDEX idx_salon_hours_salon_id ON salon_schema.salon_hours(salon_id);
CREATE INDEX idx_salon_service_salon_id ON salon_schema.salon_service(salon_id);
CREATE INDEX idx_stylist_user_id ON salon_schema.stylist(user_id);
CREATE INDEX idx_stylist_salon_stylist_id ON salon_schema.stylist_salon(stylist_id);
CREATE INDEX idx_stylist_salon_salon_id ON salon_schema.stylist_salon(salon_id);
CREATE INDEX idx_stylist_service_stylist_id ON salon_schema.stylist_service(stylist_id);
CREATE INDEX idx_stylist_service_salon_id ON salon_schema.stylist_service(salon_id);
CREATE INDEX idx_stylist_service_service_id ON salon_schema.stylist_service(service_id);
CREATE INDEX idx_salon_combo_salon_id ON salon_schema.salon_combo(salon_id);
CREATE INDEX idx_salon_combo_item_combo_id ON salon_schema.salon_combo_item(combo_id);
CREATE INDEX idx_salon_combo_item_service_id ON salon_schema.salon_combo_item(service_id);
CREATE INDEX idx_salon_staff_salon_id ON salon_schema.salon_staff(salon_id);
CREATE INDEX idx_salon_staff_user_id ON salon_schema.salon_staff(user_id);
CREATE INDEX idx_staff_invites_salon_id ON salon_schema.staff_invites(salon_id);
