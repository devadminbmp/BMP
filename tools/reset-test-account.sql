-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  RESET ONE TEST ACCOUNT — by phone number, any number. Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  ── WHAT THIS IS FOR ───────────────────────────────────────────────────────────────────────────
--  Testing signup repeatedly with the same handful of numbers. The second signup on a number is
--  CORRECTLY treated as a login — the account already exists, so the code goes to the email on the
--  account rather than the one just typed. That is not a bug and must not be "fixed": if the typed
--  email won, anybody could enter your number with their address and be sent your login code.
--
--  What it means in practice is that a number can only test the SIGNUP path once. This file makes
--  it testable again by removing the account.
--
--  ── WHY THIS REPLACED tools/delete-test-accounts.sql ───────────────────────────────────────────
--  That file had two numbers baked into it. Every new test number meant editing SQL, which meant
--  asking someone to edit SQL, which is how a five-second job becomes a message and a wait.
--  This one takes the number as a parameter.
--
--  ── USAGE ──────────────────────────────────────────────────────────────────────────────────────
--      docker cp tools\reset-test-account.sql bmp-postgres-1:/tmp/reset.sql
--      docker exec bmp-postgres-1 psql -U bmp -d bmp -v phone="'8431710204'" -f /tmp/reset.sql
--
--  Note the DOUBLE quoting: -v phone="'8431710204'". psql substitutes the value literally, so the
--  inner quotes are what make it a SQL string. Without them you get a syntax error on a bare number.
--
--  Any format works — 8431710204, +918431710204, 918431710204, even with spaces. Matching is on
--  the last ten digits, so the format you type does not have to match the format stored. That is
--  deliberate here and deliberately NOT the case in a production delete: a fuzzy match is right for
--  a developer clearing their own test data and wrong for anything touching a real person.
--
--  ⚠  HARD DELETE, LOCAL ONLY. A real erasure request goes through UserService.anonymise, which
--     keeps the row and the salon's booking history. This does not. It exists for accounts you
--     created yourself while testing, and a SQL file cannot check that — that check is you.
--
--  Runs in ONE transaction. If any part fails, nothing is deleted.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\set ON_ERROR_STOP on

\echo ''
\echo '=== BEFORE: what matches this number? ========================================='
\echo 'Matched on the last 10 digits, so any format you typed will find it.'
\echo ''

SELECT id, phone, COALESCE(name, '(no name)') AS name, COALESCE(email, '(no email)') AS email,
       default_role, created_at
FROM user_schema.users
WHERE RIGHT(regexp_replace(phone, '[^0-9]', '', 'g'), 10)
    = RIGHT(regexp_replace(:phone, '[^0-9]', '', 'g'), 10);

\echo ''
\echo '=== What else points at it? ==================================================='
\echo 'Anything above 0 either BLOCKS the delete (a real FK) or gets ORPHANED (a logical ref).'
\echo 'salon_staff is the one to look at: deleting a user who owns a salon leaves that salon'
\echo 'with no owner, and the salon itself is NOT removed by this file.'
\echo ''

WITH targets AS (
    SELECT id FROM user_schema.users
    WHERE RIGHT(regexp_replace(phone, '[^0-9]', '', 'g'), 10)
        = RIGHT(regexp_replace(:phone, '[^0-9]', '', 'g'), 10)
)
SELECT 'user_roles (FK — blocks)'        AS what, count(*) FROM user_schema.user_roles       WHERE user_id IN (SELECT id FROM targets)
UNION ALL SELECT 'refresh_tokens (FK — blocks)',   count(*) FROM user_schema.refresh_tokens    WHERE user_id IN (SELECT id FROM targets)
UNION ALL SELECT 'onboarding_state (FK — blocks)', count(*) FROM user_schema.onboarding_state  WHERE user_id IN (SELECT id FROM targets)
/*
 * contact_change_request is NOT counted here, on purpose.
 *
 * It only exists once bmp-auth's V006 has run, and there is no way to conditionally reference a
 * table inside a plain SELECT: PostgreSQL resolves every name at PARSE time, before any CASE or
 * WHERE is evaluated. A `CASE WHEN to_regclass(...) IS NULL THEN 0 ELSE (SELECT ...) END` looks
 * like it guards the reference and does not — it fails with "relation does not exist" exactly as
 * an unguarded reference would. (Tested; that is how this comment came to exist.)
 *
 * The DELETE further down handles it properly, inside a DO block, where the reference is only
 * resolved if the branch is actually taken. Nothing is lost: those rows are pending contact-change
 * codes, and there is nothing useful to report about them before a delete.
 */
UNION ALL SELECT 'salon_staff (would orphan)',     count(*) FROM salon_schema.salon_staff      WHERE user_id IN (SELECT id FROM targets)
UNION ALL SELECT 'bookings (would orphan)',        count(*) FROM booking_schema.booking        WHERE customer_id IN (SELECT id FROM targets)
UNION ALL SELECT 'support tickets (would orphan)', count(*) FROM admin_schema.support_ticket   WHERE raised_by_id IN (SELECT id FROM targets)
UNION ALL SELECT 'wallet (would orphan)',          count(*) FROM rewards_schema.wallet         WHERE user_id IN (SELECT id FROM targets)
ORDER BY 2 DESC, 1;

BEGIN;

/*
 * Children before parents. The three FK-backed tables must go first or the final DELETE is
 * rejected outright; the rest have no FK and would simply be left pointing at nothing.
 */

CREATE TEMP TABLE _targets ON COMMIT DROP AS
SELECT id FROM user_schema.users
WHERE RIGHT(regexp_replace(phone, '[^0-9]', '', 'g'), 10)
    = RIGHT(regexp_replace(:phone, '[^0-9]', '', 'g'), 10);

DELETE FROM user_schema.refresh_tokens         WHERE user_id IN (SELECT id FROM _targets);
DELETE FROM user_schema.onboarding_state       WHERE user_id IN (SELECT id FROM _targets);
DELETE FROM user_schema.user_roles             WHERE user_id IN (SELECT id FROM _targets);
/*
 * Conditional, because this table arrives with bmp-auth's V006 (Session 65) and this file has to
 * work on a database that has not been restarted since.
 *
 * A plain DELETE against a missing table aborts the whole transaction — and since everything here
 * runs as one, that would leave the account undeleted while looking like a hard failure. The
 * to_regclass check makes the statement a no-op instead: nothing to clean up because the feature
 * that creates rows here does not exist yet.
 */
DO $$
BEGIN
    IF to_regclass('user_schema.contact_change_request') IS NOT NULL THEN
        DELETE FROM user_schema.contact_change_request WHERE user_id IN (SELECT id FROM _targets);
    END IF;
END $$;

-- Salon seats. Logical refs — nothing would stop the row outliving its user.
DELETE FROM salon_schema.salon_staff           WHERE user_id IN (SELECT id FROM _targets);

/*
 * OTPs are keyed by PHONE, not by user id — so they are cleared by number, and by the same
 * last-ten-digits match. Leaving them behind would let a code issued to the old account be
 * verified against a number that no longer has one.
 */
DELETE FROM user_schema.otp_requests
 WHERE RIGHT(regexp_replace(phone, '[^0-9]', '', 'g'), 10)
     = RIGHT(regexp_replace(:phone, '[^0-9]', '', 'g'), 10);

-- The account itself, last.
DELETE FROM user_schema.users WHERE id IN (SELECT id FROM _targets);

COMMIT;

\echo ''
\echo '=== AFTER: it should be gone =================================================='
\echo ''

SELECT phone, COALESCE(name, '(no name)') AS name, COALESCE(email, '(no email)') AS email, default_role
FROM user_schema.users
ORDER BY created_at DESC
LIMIT 15;

\echo ''
\echo 'If that number is no longer listed, signup will treat it as brand new again —'
\echo 'and the email you type on the form will be the one the code goes to.'
\echo ''
