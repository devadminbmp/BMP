-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V006 — self-service contact change, verified by a code. Session 65.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── WHY A SEPARATE TABLE AND NOT A COLUMN ON otp_requests ──────────────────────────────────────
-- Because a login code and a contact-change code must never be interchangeable.
--
-- Sharing the table would mean one row type serving two purposes, and the login verifier would
-- have to remember to exclude the other kind. The day somebody forgets, a code issued to confirm
-- an email change is accepted as a login — and the whole point of issuing it separately was that
-- it proves something different. A separate table cannot be got wrong by omission: the login path
-- does not read this table at all.
--
-- ── WHAT THE CODE PROVES, AND WHAT IT DOES NOT ─────────────────────────────────────────────────
-- `sent_to_email` is where the code went, recorded per request, because it differs by case:
--
--   Changing the EMAIL  → the code goes to the NEW address. Only somebody who can read that inbox
--                         can finish, so this genuinely proves ownership of the new address.
--
--   Changing the PHONE  → the code goes to the address ALREADY on the account. This proves the
--                         REQUESTER is the account holder. It does NOT prove they own the new
--                         number, and cannot today: SMS is undeliverable until DLT registration
--                         completes (see LoggingSmsSender), so there is no way to send anything to
--                         a number nobody has verified yet.
--
-- That gap is recorded here rather than glossed, because the consequence is real: a typo in the
-- new number produces a login identity its owner cannot receive codes for. It is a self-inflicted
-- lockout rather than a takeover — nobody else gains anything — and support can correct it with
-- the account tools built in this same session. When SMS goes live, the destination for a phone
-- change moves to the new number and this becomes a genuine ownership proof.
--
-- ── SINGLE USE, SHORT LIVED, ATTEMPT LIMITED ───────────────────────────────────────────────────
-- Same three properties as the login OTP (V005), for the same reason: a six-digit code is only
-- worth anything if it cannot be replayed, cannot be sat on, and cannot be brute-forced.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS user_schema.contact_change_request (
    id            UUID PRIMARY KEY,
    user_id       UUID        NOT NULL,

    -- What they want to change TO. Both nullable, at least one required — enforced by the CHECK
    -- below rather than by the application alone, because a row with neither is meaningless and
    -- would verify successfully while changing nothing.
    new_phone     VARCHAR(20),
    new_email     VARCHAR(160),

    -- Where the code was actually delivered. Stored because it differs by case (see header) and
    -- because "where did my code go?" is the first question asked when one does not arrive.
    sent_to_email VARCHAR(160) NOT NULL,

    -- bcrypt, exactly like otp_requests. The plaintext is never stored and never logged.
    code_hash     VARCHAR(255) NOT NULL,
    attempts      INTEGER     NOT NULL DEFAULT 0,

    expires_at    TIMESTAMPTZ NOT NULL,
    -- Non-null once used. Single-use is enforced by checking this, not by deleting the row: the
    -- row is the record that the change was requested and confirmed.
    consumed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_contact_change_has_a_target
        CHECK (new_phone IS NOT NULL OR new_email IS NOT NULL)
);

-- The only lookup: the newest unconsumed request for this user.
CREATE INDEX IF NOT EXISTS ix_contact_change_user
    ON user_schema.contact_change_request (user_id, created_at DESC);

COMMENT ON TABLE user_schema.contact_change_request IS
    'Self-service phone/email change, Session 65. Deliberately NOT otp_requests — a login code and '
    'a contact-change code must never be interchangeable.';
COMMENT ON COLUMN user_schema.contact_change_request.sent_to_email IS
    'Email change: the NEW address (proves ownership). Phone change: the address already on the '
    'account (proves the requester only) — SMS cannot deliver until DLT registration completes.';
