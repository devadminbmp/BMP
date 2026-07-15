-- V007__review_schema.sql
-- Schema: review_schema  |  Module: bmp-review
-- Generated from the locked table definitions in CONTEXT.md.
-- NOTE: bmp-review/package-info.java documented a simpler 3-table version
-- (review, review_photo, salon_response); implementing CONTEXT.md's fuller
-- 6-table design instead, same reasoning as payment_schema. Flagged.

CREATE SCHEMA IF NOT EXISTS review_schema;

CREATE TABLE review_schema.review (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- UK NOT NULL — no booking, no review
    salon_id UUID NOT NULL,
    stylist_id UUID,
    salon_rating SMALLINT NOT NULL,  -- 1-5
    stylist_rating SMALLINT,  -- 1-5, mandatory only if stylist assigned
    review_text TEXT,
    edit_locked_at TIMESTAMPTZ NOT NULL,  -- = created_at + 7 days
    needs_remoderation BOOLEAN NOT NULL,  -- set true on text edits
    community_post_id VARCHAR(36),  -- MongoDB ObjectId bridge key
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE review_schema.review_edit_history (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    review_id UUID NOT NULL,  -- FK -> review.id
    version INT NOT NULL,  -- 1 = original, never overwrites
    salon_rating SMALLINT NOT NULL,
    stylist_rating SMALLINT,
    review_text TEXT,
    salon_response_hidden BOOLEAN NOT NULL,  -- hidden if review edited to low rating
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE review_schema.review_prompt (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,
    send_after TIMESTAMPTZ NOT NULL,  -- 30 minutes after booking.completed
    expires_at TIMESTAMPTZ NOT NULL,  -- send_after + 7 days
    channel VARCHAR(10) NOT NULL,  -- whatsapp first, push fallback
    sent_at TIMESTAMPTZ
);

CREATE TABLE review_schema.salon_rating_snapshot (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- UK
    overall_rating NUMERIC(3,2) NOT NULL,  -- equal weight forever
    total_reviews INT NOT NULL,
    reviews_last_30_days INT NOT NULL,  -- trend indicator only
    rating_last_30_days NUMERIC(3,2),  -- trend indicator only
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE review_schema.stylist_rating_snapshot (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    stylist_id UUID NOT NULL,
    salon_id UUID,  -- per-salon rating, FROZEN on departure
    salon_rating NUMERIC(3,2),
    overall_rating NUMERIC(3,2) NOT NULL,  -- cross-salon lifetime
    total_reviews INT NOT NULL,
    qualifies_top_stylist BOOLEAN NOT NULL,  -- total_reviews>=50 AND overall_rating>=4.7
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE review_schema.salon_response (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    review_id UUID NOT NULL,  -- FK -> review.id
    salon_id UUID NOT NULL,
    response_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL  -- editable within 24h
);

-- Foreign keys within review_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE review_schema.review_edit_history ADD CONSTRAINT fk_review_edit_history_review_id FOREIGN KEY (review_id) REFERENCES review_schema.review(id);
ALTER TABLE review_schema.salon_response ADD CONSTRAINT fk_salon_response_review_id FOREIGN KEY (review_id) REFERENCES review_schema.review(id);

-- Append-only tables: DELETE/UPDATE revoked at DB level (Schema Rule).
-- <app_role> must be replaced with the actual DB role bmp-app connects as
-- (see bmp-app/src/main/resources/application.yml spring.datasource.username).
REVOKE UPDATE, DELETE ON review_schema.review_edit_history FROM PUBLIC;

-- Indexes on FK / lookup columns
CREATE INDEX idx_review_booking_id ON review_schema.review(booking_id);
CREATE INDEX idx_review_salon_id ON review_schema.review(salon_id);
CREATE INDEX idx_review_stylist_id ON review_schema.review(stylist_id);
CREATE INDEX idx_review_community_post_id ON review_schema.review(community_post_id);
CREATE INDEX idx_review_edit_history_review_id ON review_schema.review_edit_history(review_id);
CREATE INDEX idx_review_prompt_booking_id ON review_schema.review_prompt(booking_id);
CREATE INDEX idx_salon_rating_snapshot_salon_id ON review_schema.salon_rating_snapshot(salon_id);
CREATE INDEX idx_stylist_rating_snapshot_stylist_id ON review_schema.stylist_rating_snapshot(stylist_id);
CREATE INDEX idx_stylist_rating_snapshot_salon_id ON review_schema.stylist_rating_snapshot(salon_id);
CREATE INDEX idx_salon_response_review_id ON review_schema.salon_response(review_id);
CREATE INDEX idx_salon_response_salon_id ON review_schema.salon_response(salon_id);
