-- V005__booking_schema.sql
-- Schema: booking_schema  |  Module: bmp-booking
-- Generated from the locked table definitions in CONTEXT.md.

CREATE SCHEMA IF NOT EXISTS booking_schema;

CREATE TABLE booking_schema.booking (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_ref VARCHAR(20) NOT NULL,  -- UK, human-readable e.g. BMP-2026-08291
    salon_id UUID NOT NULL,  -- real FK -> salon_schema.salon
    customer_id UUID NOT NULL,  -- real FK -> user_schema.users
    status VARCHAR(20) NOT NULL,  -- reuses existing enum in booking.api
    final_amount_paise BIGINT NOT NULL,  -- what Razorpay charged
    total_refunded_paise BIGINT NOT NULL,  -- running refund total
    commission_paise BIGINT NOT NULL,  -- FROZEN at booking creation
    policy_snapshot JSONB NOT NULL,  -- FROZEN copy of salon_policy, never changes
    refund_window_open BOOLEAN NOT NULL,  -- false after 7 days from scheduled_start
    confirmed_at TIMESTAMPTZ,  -- SET BY WEBHOOK ONLY — never by app code directly
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE booking_schema.booking_service_item (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- FK -> booking.id
    service_id UUID NOT NULL,  -- logical ref -> salon_schema.salon_service
    assigned_stylist_id UUID,
    selection_type VARCHAR(20) NOT NULL,  -- specific_stylist/any_available/walk_in_pool
    service_start TIMESTAMPTZ NOT NULL,
    service_end TIMESTAMPTZ NOT NULL,
    name_snapshot VARCHAR(160) NOT NULL,  -- FROZEN at creation
    price_paise_snapshot BIGINT NOT NULL,  -- FROZEN at creation
    duration_shown_minutes INT NOT NULL,  -- FROZEN at creation
    actual_duration_minutes INT NOT NULL,  -- FROZEN at creation, never shown to customer
    item_status VARCHAR(10) NOT NULL  -- active/removed/completed
);

CREATE TABLE booking_schema.booking_events (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- FK -> booking.id
    event_type VARCHAR(40) NOT NULL,  -- one of 20 event types
    actor_type VARCHAR(20) NOT NULL,  -- customer/salon/system/bmp_staff
    actor_id UUID,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE booking_schema.slot_lock (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    stylist_id UUID NOT NULL,
    booking_id UUID,
    lock_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,  -- NOW + 5 minutes; Redis is the live lock, this is for analytics
    release_reason VARCHAR(20),  -- booking_confirmed/payment_failed/lock_expired/manual
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE booking_schema.booking_disruption (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_service_item_id UUID NOT NULL,  -- FK -> booking_service_item.id
    notify_customer BOOLEAN NOT NULL,  -- false if selection_type=any_available
    salon_deadline TIMESTAMPTZ NOT NULL,  -- notified_at + 2h
    rejection_count SMALLINT NOT NULL,  -- after 2 -> escalated
    customer_acceptance VARCHAR(20) NOT NULL,  -- pending/accepted/rejected/not_required
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE booking_schema.booking_modification (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- FK -> booking.id
    before_snapshot JSONB NOT NULL,
    after_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE booking_schema.refund_ticket (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- FK -> booking.id
    raised_by_role VARCHAR(20) NOT NULL,  -- salon_owner/manager/customer/bmp_system/bmp_admin
    amount_paise BIGINT NOT NULL,  -- minimum 1000 (Rs 10)
    commission_absorbed_by VARCHAR(10) NOT NULL,  -- salon/bmp
    status VARCHAR(20) NOT NULL,  -- raised/approved/rejected/executed
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE booking_schema.refund_guard (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- UK, FK -> booking.id
    max_refundable_paise BIGINT NOT NULL,  -- hard ceiling = booking.final_amount_paise
    total_refunded_paise BIGINT NOT NULL  -- checked/updated atomically on every new refund ticket
);

-- Foreign keys within booking_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE booking_schema.booking_service_item ADD CONSTRAINT fk_booking_service_item_booking_id FOREIGN KEY (booking_id) REFERENCES booking_schema.booking(id);
ALTER TABLE booking_schema.booking_events ADD CONSTRAINT fk_booking_events_booking_id FOREIGN KEY (booking_id) REFERENCES booking_schema.booking(id);
ALTER TABLE booking_schema.booking_disruption ADD CONSTRAINT fk_booking_disruption_booking_service_item_id FOREIGN KEY (booking_service_item_id) REFERENCES booking_schema.booking_service_item(id);
ALTER TABLE booking_schema.booking_modification ADD CONSTRAINT fk_booking_modification_booking_id FOREIGN KEY (booking_id) REFERENCES booking_schema.booking(id);
ALTER TABLE booking_schema.refund_ticket ADD CONSTRAINT fk_refund_ticket_booking_id FOREIGN KEY (booking_id) REFERENCES booking_schema.booking(id);
ALTER TABLE booking_schema.refund_guard ADD CONSTRAINT fk_refund_guard_booking_id FOREIGN KEY (booking_id) REFERENCES booking_schema.booking(id);

-- Append-only tables: DELETE/UPDATE revoked at DB level (Schema Rule).
-- <app_role> must be replaced with the actual DB role bmp-app connects as
-- (see bmp-app/src/main/resources/application.yml spring.datasource.username).
REVOKE UPDATE, DELETE ON booking_schema.booking_events FROM PUBLIC;

-- Indexes on FK / lookup columns
CREATE INDEX idx_booking_salon_id ON booking_schema.booking(salon_id);
CREATE INDEX idx_booking_customer_id ON booking_schema.booking(customer_id);
CREATE INDEX idx_booking_service_item_booking_id ON booking_schema.booking_service_item(booking_id);
CREATE INDEX idx_booking_service_item_service_id ON booking_schema.booking_service_item(service_id);
CREATE INDEX idx_booking_service_item_assigned_stylist_id ON booking_schema.booking_service_item(assigned_stylist_id);
CREATE INDEX idx_booking_events_booking_id ON booking_schema.booking_events(booking_id);
CREATE INDEX idx_booking_events_actor_id ON booking_schema.booking_events(actor_id);
CREATE INDEX idx_slot_lock_stylist_id ON booking_schema.slot_lock(stylist_id);
CREATE INDEX idx_slot_lock_booking_id ON booking_schema.slot_lock(booking_id);
CREATE INDEX idx_booking_disruption_booking_service_item_id ON booking_schema.booking_disruption(booking_service_item_id);
CREATE INDEX idx_booking_modification_booking_id ON booking_schema.booking_modification(booking_id);
CREATE INDEX idx_refund_ticket_booking_id ON booking_schema.refund_ticket(booking_id);
CREATE INDEX idx_refund_guard_booking_id ON booking_schema.refund_guard(booking_id);
