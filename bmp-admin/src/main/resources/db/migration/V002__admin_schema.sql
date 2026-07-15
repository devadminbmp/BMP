-- V008__admin_schema.sql
-- Schema: admin_schema  |  Module: bmp-admin
-- Generated from the locked table definitions in CONTEXT.md.

CREATE SCHEMA IF NOT EXISTS admin_schema;

CREATE TABLE admin_schema.bmp_staff (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    name VARCHAR(120) NOT NULL,
    phone VARCHAR(20) NOT NULL,  -- UK
    email VARCHAR(160),  -- UK
    password_hash VARCHAR(255) NOT NULL,  -- bcrypt — staff use password login, NOT OTP
    role VARCHAR(20) NOT NULL,  -- super_admin/ops_admin/support_agent/finance_admin
    status VARCHAR(20) NOT NULL,  -- active/suspended/deactivated
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admin_schema.support_ticket (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    ticket_ref VARCHAR(20) NOT NULL,  -- UK, TCK-2026-00042
    raised_by_type VARCHAR(20) NOT NULL,  -- customer/salon_owner/manager/bmp_staff
    raised_by_id UUID NOT NULL,  -- logical ref
    booking_id UUID,
    category VARCHAR(30) NOT NULL,  -- booking_issue/payment_issue/refund_dispute/account_issue/stylist_complaint/other
    subject VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,  -- open/in_progress/waiting_on_user/resolved/closed
    priority VARCHAR(10) NOT NULL,  -- low/medium/high/urgent
    assigned_staff_id UUID,  -- logical ref -> bmp_staff.id
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ
);

CREATE TABLE admin_schema.support_message (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    ticket_id UUID NOT NULL,  -- FK -> support_ticket.id
    sender_type VARCHAR(20) NOT NULL,  -- customer/bmp_staff
    sender_id UUID NOT NULL,
    message_text TEXT NOT NULL,
    attachment_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admin_schema.audit_log (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    actor_type VARCHAR(20) NOT NULL,  -- bmp_staff/system/salon_owner/customer
    actor_id UUID,
    action VARCHAR(100) NOT NULL,  -- e.g. refund.approved, salon.suspended
    entity_type VARCHAR(60) NOT NULL,
    entity_id UUID NOT NULL,
    metadata JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL
);

-- Foreign keys within admin_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE admin_schema.support_message ADD CONSTRAINT fk_support_message_ticket_id FOREIGN KEY (ticket_id) REFERENCES admin_schema.support_ticket(id);

-- Append-only tables: DELETE/UPDATE revoked at DB level (Schema Rule).
-- <app_role> must be replaced with the actual DB role bmp-app connects as
-- (see bmp-app/src/main/resources/application.yml spring.datasource.username).
REVOKE UPDATE, DELETE ON admin_schema.audit_log FROM PUBLIC;

-- Indexes on FK / lookup columns
CREATE INDEX idx_support_ticket_raised_by_id ON admin_schema.support_ticket(raised_by_id);
CREATE INDEX idx_support_ticket_booking_id ON admin_schema.support_ticket(booking_id);
CREATE INDEX idx_support_ticket_assigned_staff_id ON admin_schema.support_ticket(assigned_staff_id);
CREATE INDEX idx_support_message_ticket_id ON admin_schema.support_message(ticket_id);
CREATE INDEX idx_support_message_sender_id ON admin_schema.support_message(sender_id);
CREATE INDEX idx_audit_log_actor_id ON admin_schema.audit_log(actor_id);
CREATE INDEX idx_audit_log_entity_id ON admin_schema.audit_log(entity_id);
