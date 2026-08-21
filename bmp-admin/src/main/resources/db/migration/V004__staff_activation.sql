-- V004 — how a new employee gets their credentials.
--
-- ============================================================================================
-- THE DECISION: A MASTER ADMIN CREATES THE ACCOUNT, BUT NEVER THE PASSWORD
-- ============================================================================================
-- The obvious design is "superadmin types a password and tells the employee". Don't:
--
--   · The admin then KNOWS a colleague's password. Every action that employee ever takes is
--     deniable — "someone else could have logged in as me" is suddenly true, which destroys
--     the audit log's value as evidence.
--   · The password travels over WhatsApp or is said aloud in an office.
--   · People don't change it. The admin's chosen password stays forever.
--
-- So instead: the master admin creates the ACCOUNT and gets a one-time activation code. The
-- employee redeems it, sets their own password nobody else has ever seen, and enrols 2FA in
-- the same flow. Same mechanism as the salon manager invites in bmp-salon — one pattern,
-- reviewed once.
--
-- The code is single-use, expires in 48 hours, and is stored HASHED: a leak of this table
-- yields nothing usable, exactly like the refresh tokens next door.
-- ============================================================================================

CREATE TABLE IF NOT EXISTS admin_schema.staff_activation (
    id UUID PRIMARY KEY NOT NULL,
    staff_id UUID NOT NULL,
    -- SHA-256 of the code. Never the code itself — see the note above.
    code_hash VARCHAR(120) NOT NULL,
    -- 'activation' for a new employee, 'reset' when a master admin re-issues one for somebody
    -- locked out. Same table because the redemption flow is identical; the distinction only
    -- matters for the audit trail.
    purpose VARCHAR(20) NOT NULL DEFAULT 'activation',
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    -- Attempts are counted so a guessable code can't be brute-forced offline-style over HTTP.
    attempt_count INT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,                  -- the master admin who issued it
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_staff_activation_staff
    ON admin_schema.staff_activation(staff_id, consumed_at);

ALTER TABLE admin_schema.staff_activation
    ADD CONSTRAINT fk_staff_activation_staff
    FOREIGN KEY (staff_id) REFERENCES admin_schema.bmp_staff(id);

-- A staff member who hasn't activated yet is 'invited', not 'active'. The login flow refuses
-- anything that isn't 'active', so an un-redeemed account cannot be signed into even if
-- someone guesses the email.
--
-- V002 created status without a constraint; documenting the vocabulary here rather than adding
-- a CHECK, because the existing rows predate this and a failed migration on a live database is
-- a worse outcome than a documented convention.
COMMENT ON COLUMN admin_schema.bmp_staff.status IS
    'invited | active | suspended | offboarded. Only ''active'' may sign in.';
