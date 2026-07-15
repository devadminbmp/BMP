-- V004__availability_model.sql
-- Schema: salon_schema  |  Module: bmp-salon
-- Generated from the locked table definitions in CONTEXT.md.
-- THE AVAILABILITY MODEL. Previously blocked (see V001's trailing comment and
-- AvailabilityApi.java's javadoc: 'do NOT write this before the paper design
-- against 3 real salons is approved'). Unblocked this session after drafting
-- and reviewing answers to all 6 open design questions with Darshan.
-- FLAG: this was signed off by Darshan only, NOT all 3 founders as the process
-- calls for. See CONTEXT.md Session Log for the full Q1-Q6 answers and this flag
-- — Shivam and Achyuth should review/ratify or amend before treating it as final.
-- slot_granularity_minutes now lives on salon_policy (V003) as a per-salon override,
-- default 15 minutes — Q1's answer, refined to be configurable per salon.

CREATE SCHEMA IF NOT EXISTS salon_schema;

CREATE TABLE salon_schema.stylist_availability (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    stylist_id UUID NOT NULL,  -- FK -> stylist.id
    salon_id UUID NOT NULL,  -- FK -> salon.id — a stylist's availability is per salon
    rule_type VARCHAR(20) NOT NULL,  -- weekly_template/exception/leave — Q3
    day_of_week SMALLINT,  -- 0-6, used when rule_type=weekly_template
    specific_date DATE,  -- used when rule_type=exception or leave
    slot_type VARCHAR(10) NOT NULL,  -- working/break/leave
    start_time TIME,  -- NULL for a full-day leave row
    end_time TIME,
    blocks_booking BOOLEAN NOT NULL,  -- true for break/leave, false for working
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE salon_schema.walk_in_block (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    salon_id UUID NOT NULL,  -- FK -> salon.id
    stylist_id UUID NOT NULL,  -- FK -> stylist.id
    block_date DATE NOT NULL,
    start_time TIME NOT NULL,  -- rounded to salon_policy.slot_granularity_minutes grid — Q2
    duration_minutes INT NOT NULL,
    created_by_staff_id UUID,  -- logical ref -> salon_staff or bmp_staff
    created_at TIMESTAMPTZ NOT NULL
);

-- Foreign keys within salon_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE salon_schema.stylist_availability ADD CONSTRAINT fk_stylist_availability_stylist_id FOREIGN KEY (stylist_id) REFERENCES salon_schema.stylist(id);
ALTER TABLE salon_schema.stylist_availability ADD CONSTRAINT fk_stylist_availability_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.walk_in_block ADD CONSTRAINT fk_walk_in_block_salon_id FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);
ALTER TABLE salon_schema.walk_in_block ADD CONSTRAINT fk_walk_in_block_stylist_id FOREIGN KEY (stylist_id) REFERENCES salon_schema.stylist(id);

-- Indexes on FK / lookup columns
CREATE INDEX idx_stylist_availability_stylist_id ON salon_schema.stylist_availability(stylist_id);
CREATE INDEX idx_stylist_availability_salon_id ON salon_schema.stylist_availability(salon_id);
CREATE INDEX idx_walk_in_block_salon_id ON salon_schema.walk_in_block(salon_id);
CREATE INDEX idx_walk_in_block_stylist_id ON salon_schema.walk_in_block(stylist_id);
CREATE INDEX idx_walk_in_block_created_by_staff_id ON salon_schema.walk_in_block(created_by_staff_id);
