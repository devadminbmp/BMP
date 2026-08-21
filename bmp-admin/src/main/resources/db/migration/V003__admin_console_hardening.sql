-- V003 — bmp-admin: staff security hardening, SLA tracking, moderation, and DPDP data requests.
--
-- ============================================================================================
-- WHY THIS IS ADDITIVE, NOT A REWRITE
-- ============================================================================================
-- V002 already created bmp_staff, support_ticket, support_message and audit_log. Those tables
-- (and the entities/services on top of them) are in use, and a Flyway migration that has run
-- anywhere is immutable. So this file only ADDs — new columns default sensibly, and every
-- pre-existing row stays valid with no backfill.
--
-- ============================================================================================
-- THE RULE THIS SCHEMA EXISTS TO ENFORCE
-- ============================================================================================
-- BMP staff are NOT users. There is no row in user_schema.users for a support agent, and no
-- role on a customer account grants console access. Separate schema, separate service,
-- separate credentials, separate token audience.
--
-- This is not tidiness. An internal console is the highest-value target in a consumer product:
-- it reads every customer's phone number and every salon's takings. If staff identity lived in
-- the customer users table, one privilege-escalation bug anywhere in the customer app would be
-- a total compromise. Two identity systems means an attacker has to break both.
--
-- salon_schema V003 already anticipated this seam:
--   `created_by_staff_id UUID -- logical ref -> salon_staff or bmp_staff`
-- ============================================================================================

-- --------------------------------------------------------------------------------------------
-- 1. bmp_staff — uniqueness that V002 documented but never enforced, plus 2FA and lockout
-- --------------------------------------------------------------------------------------------

-- V002's comments said "-- UK" on email and phone, but no constraint was created. Two staff
-- accounts on one email is an authentication ambiguity, not an inconvenience.
CREATE UNIQUE INDEX IF NOT EXISTS uk_bmp_staff_email ON admin_schema.bmp_staff(lower(email))
    WHERE email IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_bmp_staff_phone ON admin_schema.bmp_staff(phone);
CREATE INDEX IF NOT EXISTS idx_bmp_staff_status ON admin_schema.bmp_staff(status);

-- TOTP (RFC 6238) second factor. A password alone protecting a console that can read every
-- customer's personal data is not defensible in 2026 — and password reuse is universal.
--
-- NULL secret means "not enrolled yet". Login is refused past the password step until the
-- staff member enrols, so 2FA cannot be quietly skipped by anyone, including whoever set the
-- account up.
ALTER TABLE admin_schema.bmp_staff ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(64);
ALTER TABLE admin_schema.bmp_staff ADD COLUMN IF NOT EXISTS totp_enrolled_at TIMESTAMPTZ;

-- Brute-force protection, same shape as bmp-auth's OTP lockout so there's one mental model.
ALTER TABLE admin_schema.bmp_staff ADD COLUMN IF NOT EXISTS failed_login_count INT NOT NULL DEFAULT 0;
ALTER TABLE admin_schema.bmp_staff ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

-- Who created this account. Onboarding provenance is the first question asked after an
-- incident, and "nobody knows" is the wrong answer.
ALTER TABLE admin_schema.bmp_staff ADD COLUMN IF NOT EXISTS created_by UUID;

-- --------------------------------------------------------------------------------------------
-- 2. staff_session — server-side sessions we can actually revoke
-- --------------------------------------------------------------------------------------------
-- selector.verifier split, same as bmp-auth: the selector is indexed and the verifier is
-- hashed, so a database leak yields no usable sessions.
--
-- Staff sessions are deliberately SHORT-lived. A customer stays signed in for 30 days; a
-- support console session that outlives someone's shift — on a shared machine, in an office —
-- is a liability. Recording IP and user agent means "whose session was that?" is answerable.

CREATE TABLE IF NOT EXISTS admin_schema.staff_session (
    id UUID PRIMARY KEY NOT NULL,
    staff_id UUID NOT NULL,
    selector VARCHAR(40) NOT NULL,
    verifier_hash VARCHAR(120) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    ip_address VARCHAR(64),
    user_agent VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_staff_session_selector ON admin_schema.staff_session(selector);
CREATE INDEX IF NOT EXISTS idx_staff_session_staff ON admin_schema.staff_session(staff_id, revoked);
ALTER TABLE admin_schema.staff_session
    ADD CONSTRAINT fk_staff_session_staff FOREIGN KEY (staff_id) REFERENCES admin_schema.bmp_staff(id);

-- --------------------------------------------------------------------------------------------
-- 3. audit_log — enough context to still be readable in a year
-- --------------------------------------------------------------------------------------------

-- Denormalised actor identity. A log that says only "actor_id 0193…" is useless once that
-- staff member has left and their row has been edited: the log must record who they WERE.
ALTER TABLE admin_schema.audit_log ADD COLUMN IF NOT EXISTS actor_email VARCHAR(160);
ALTER TABLE admin_schema.audit_log ADD COLUMN IF NOT EXISTS actor_role VARCHAR(20);

-- WHY the action was taken. Required by the application for anything that reveals personal
-- data. The real deterrent against misuse of an internal console isn't permissions — staff
-- need the access to do the job — it's the certainty of being logged, by name, with a reason.
ALTER TABLE admin_schema.audit_log ADD COLUMN IF NOT EXISTS justification TEXT;

-- The console's default view is "what happened recently", which V002 had no index for.
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON admin_schema.audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON admin_schema.audit_log(action, created_at DESC);

-- --------------------------------------------------------------------------------------------
-- 4. support_ticket — SLA tracking and requester contact
-- --------------------------------------------------------------------------------------------

-- first_response_due_at is the field that matters most to an actual customer: silence is what
-- makes people angry, far more than a slow resolution.
ALTER TABLE admin_schema.support_ticket ADD COLUMN IF NOT EXISTS first_response_due_at TIMESTAMPTZ;
ALTER TABLE admin_schema.support_ticket ADD COLUMN IF NOT EXISTS first_responded_at TIMESTAMPTZ;
ALTER TABLE admin_schema.support_ticket ADD COLUMN IF NOT EXISTS resolution_due_at TIMESTAMPTZ;

-- V002 assumed every ticket has a raised_by_id — but a walk-in complaint, or an email from
-- someone who never finished signing up, has no account. Without these the agent has no way
-- to reply.
ALTER TABLE admin_schema.support_ticket ADD COLUMN IF NOT EXISTS requester_email VARCHAR(160);
ALTER TABLE admin_schema.support_ticket ADD COLUMN IF NOT EXISTS requester_phone VARCHAR(20);
ALTER TABLE admin_schema.support_ticket ADD COLUMN IF NOT EXISTS salon_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uk_support_ticket_ref ON admin_schema.support_ticket(ticket_ref);
-- Partial index: the SLA queue only ever asks about tickets that haven't been answered.
CREATE INDEX IF NOT EXISTS idx_support_ticket_sla ON admin_schema.support_ticket(first_response_due_at)
    WHERE first_responded_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_support_ticket_queue
    ON admin_schema.support_ticket(status, priority, created_at);

-- Internal notes must never reach the customer. This is the classic support-tool disaster, so
-- it's a column with a safe default rather than a naming convention.
ALTER TABLE admin_schema.support_message ADD COLUMN IF NOT EXISTS internal_note BOOLEAN NOT NULL DEFAULT FALSE;

-- Canned responses. Consistency in support answers is a quality feature, not laziness — and it
-- stops six agents inventing six different refund explanations.
CREATE TABLE IF NOT EXISTS admin_schema.canned_response (
    id UUID PRIMARY KEY NOT NULL,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(30),
    body TEXT NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- --------------------------------------------------------------------------------------------
-- 5. salon_review — the gate on the marketplace
-- --------------------------------------------------------------------------------------------
-- A salon that signs up is NOT immediately visible to customers. Somebody checks it is a real
-- business at a real address first. Without this, "list your salon" is an open door to putting
-- anything in front of your customers, under your brand.

CREATE TABLE IF NOT EXISTS admin_schema.salon_review (
    id UUID PRIMARY KEY NOT NULL,
    salon_id UUID NOT NULL,                    -- logical ref -> salon_schema.salon
    status VARCHAR(20) NOT NULL,               -- pending | approved | rejected | suspended
    submitted_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by UUID,                           -- logical ref -> bmp_staff.id
    -- Shown to the owner on rejection, and REQUIRED by the service for that transition:
    -- "no" without a reason generates a support ticket every single time.
    decision_note TEXT,
    -- Which checks the reviewer actually performed (GST seen, address verified, photos genuine).
    checks JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_salon_review_salon ON admin_schema.salon_review(salon_id);
CREATE INDEX IF NOT EXISTS idx_salon_review_status ON admin_schema.salon_review(status, submitted_at);

-- Reported content. Generic on purpose: a new reportable thing shouldn't need a new table.
CREATE TABLE IF NOT EXISTS admin_schema.content_report (
    id UUID PRIMARY KEY NOT NULL,
    content_type VARCHAR(40) NOT NULL,         -- review | salon_photo | salon_profile | stylist_profile
    content_id UUID NOT NULL,
    salon_id UUID,
    reported_by_user_id UUID,                  -- NULL when raised internally
    reason VARCHAR(60) NOT NULL,
    detail TEXT,
    status VARCHAR(20) NOT NULL,               -- open | upheld | dismissed
    resolved_by UUID,
    resolved_at TIMESTAMPTZ,
    resolution_note TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_content_report_status ON admin_schema.content_report(status, created_at);
CREATE INDEX IF NOT EXISTS idx_content_report_content ON admin_schema.content_report(content_type, content_id);

-- --------------------------------------------------------------------------------------------
-- 6. data_request — DPDP Act 2023 obligations
-- --------------------------------------------------------------------------------------------
-- Indian law gives users the right to access and erase their personal data, on a clock. This
-- table is how you PROVE you honoured a request: "we definitely did it" is not a defence
-- without a record, and the deadline is not advisory.

CREATE TABLE IF NOT EXISTS admin_schema.data_request (
    id UUID PRIMARY KEY NOT NULL,
    request_type VARCHAR(20) NOT NULL,         -- export | delete | correct
    subject_user_id UUID NOT NULL,             -- logical ref -> user_schema.users
    subject_email VARCHAR(160),
    status VARCHAR(20) NOT NULL,               -- received | verifying | in_progress | completed | rejected
    -- Identity MUST be verified before acting. Honouring a forged deletion request is itself a
    -- data breach, and a forged export request is worse.
    identity_verified_at TIMESTAMPTZ,
    verified_by UUID,
    due_at TIMESTAMPTZ NOT NULL,               -- statutory deadline
    completed_at TIMESTAMPTZ,
    completed_by UUID,
    rejection_reason TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_data_request_status ON admin_schema.data_request(status, due_at);
CREATE INDEX IF NOT EXISTS idx_data_request_subject ON admin_schema.data_request(subject_user_id);

-- --------------------------------------------------------------------------------------------
-- 7. platform_setting — turn something off at 2am without a deploy
-- --------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS admin_schema.platform_setting (
    id UUID PRIMARY KEY NOT NULL,
    setting_key VARCHAR(80) NOT NULL,
    setting_value TEXT NOT NULL,
    value_type VARCHAR(20) NOT NULL,           -- boolean | number | string | json
    description TEXT,
    updated_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_setting_key ON admin_schema.platform_setting(setting_key);

INSERT INTO admin_schema.platform_setting (id, setting_key, setting_value, value_type, description, created_at, updated_at)
VALUES
  ('01930000-0000-7000-8000-000000000010', 'salon_auto_approve', 'false', 'boolean',
   'When true, new salons skip the manual review queue. Intended for a controlled pilot only.', NOW(), NOW()),
  ('01930000-0000-7000-8000-000000000011', 'support_first_response_hours', '4', 'number',
   'Hours allowed for a first response before a ticket breaches SLA.', NOW(), NOW()),
  ('01930000-0000-7000-8000-000000000012', 'data_request_due_days', '30', 'number',
   'Days to fulfil a data subject request. Confirm against DPDP rules before launch.', NOW(), NOW()),
  ('01930000-0000-7000-8000-000000000013', 'new_bookings_enabled', 'true', 'boolean',
   'Kill switch: set false to stop all new bookings platform-wide during an incident.', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------------------------
-- 8. The first superadmin
-- --------------------------------------------------------------------------------------------
-- A console nobody can sign into is useless. A console with a password written in a migration
-- is worse — migrations live in git forever, get copied into staging, and are read by everyone
-- who ever clones the repo. And a self-service "create the first admin" screen is a well-known
-- way to hand your console to whoever finds it first.
--
-- So: the account is seeded, but WITH NO USABLE PASSWORD.
--
-- The hash below is deliberately not a bcrypt hash at all. bcrypt verification against it can
-- only ever fail, so this account cannot be signed into until somebody deliberately sets a
-- credential. That is the point: there is no default password to leak, guess, or forget to
-- change. The account is also seeded WITHOUT a TOTP secret, so whoever claims it is forced
-- through 2FA enrolment before they can do anything.
--
-- ⚠️ AN EARLIER DRAFT OF THIS FILE SEEDED A WELL-KNOWN PUBLIC BCRYPT HASH taken from Spring
-- Security example code. If that version was ever applied to a database, run:
--     UPDATE admin_schema.bmp_staff SET password_hash = 'LOCKED-NO-PASSWORD-SET'
--     WHERE id = '01930000-0000-7000-8000-000000000001';
-- and bootstrap it properly as below.
--
-- TO CLAIM THIS ACCOUNT (documented in bmp-admin/README):
--   Set BMP_ADMIN_BOOTSTRAP_EMAIL and BMP_ADMIN_BOOTSTRAP_PASSWORD in the environment. On
--   startup, bmp-admin sets the password for that account IF AND ONLY IF it currently has no
--   usable one — so the variables are inert on every subsequent boot and cannot be used to
--   silently reset a live account.
--
-- TODO(pre-launch): change the email to a real, owned work address.

INSERT INTO admin_schema.bmp_staff (
    id, name, phone, email, password_hash, role, status, created_at, updated_at
) VALUES (
    '01930000-0000-7000-8000-000000000001',
    'BMP Superadmin',
    '+910000000001',
    'devadmin.bmp@gmail.com',
    'LOCKED-NO-PASSWORD-SET',   -- not a bcrypt hash: verification always fails, by design
    'super_admin',
    'active',
    NOW(),
    NOW()
) ON CONFLICT DO NOTHING;
