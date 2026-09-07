-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  DELETE TWO TEST ACCOUNTS — +919113639755 and +919663831388. Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  WHY THIS IS A SCRIPT AND NOT A ONE-LINE DELETE
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  `DELETE FROM users WHERE phone = ...` fails. Three real foreign keys point at user_schema.users
--  (user_roles, refresh_tokens, onboarding_state), so the delete is rejected and you get a
--  constraint error rather than a result.
--
--  Worse, a dozen tables in OTHER schemas hold a user id as a LOGICAL reference with no FK —
--  bookings, tickets, wallets, referrals, salon staff. The database will not stop you orphaning
--  those. So this script looks BEFORE it deletes and tells you what it found.
--
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  ⚠ THIS IS A HARD DELETE. IT IS NOT WHAT PRODUCTION SHOULD USE.
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  A real customer asking to be removed goes through UserService.anonymise (V005): the row stays,
--  identifying fields are cleared, and the bookings a salon needs for its own accounting survive.
--  That is the DPDP-correct behaviour and it is deliberately NOT what this file does.
--
--  This exists because these two are YOUR OWN test accounts, created minutes apart while chasing
--  a bug, and you want them gone rather than tombstoned. Do not reach for this file for a real
--  person — the console's account tools are for that.
--
--  USAGE
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--    docker cp tools\delete-test-accounts.sql bmp-postgres-1:/tmp/del.sql
--    docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/del.sql
--
--  Runs in ONE transaction. If any part fails, nothing is deleted.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\echo ''
\echo '=== BEFORE: what are we about to delete? ======================================'
\echo ''

SELECT id, phone, COALESCE(name,'(no name)') AS name, email, default_role, created_at
FROM user_schema.users
WHERE phone IN ('+919113639755', '+919663831388');

\echo ''
\echo '=== What else references these accounts? ======================================'
\echo 'Anything with a count > 0 will be ORPHANED (no FK to stop it) or blocks the delete.'
\echo 'A salon owned by one of these would be the serious one — check `salon_staff`.'
\echo ''

WITH targets AS (
    SELECT id FROM user_schema.users WHERE phone IN ('+919113639755', '+919663831388')
)
SELECT 'user_roles (FK — blocks)'     AS what, count(*) FROM user_schema.user_roles     WHERE user_id IN (SELECT id FROM targets)
UNION ALL SELECT 'refresh_tokens (FK — blocks)',  count(*) FROM user_schema.refresh_tokens  WHERE user_id IN (SELECT id FROM targets)
UNION ALL SELECT 'onboarding_state (FK — blocks)', count(*) FROM user_schema.onboarding_state WHERE user_id IN (SELECT id FROM targets)
UNION ALL SELECT 'salon_staff (would orphan)',    count(*) FROM salon_schema.salon_staff    WHERE user_id IN (SELECT id FROM targets)
UNION ALL SELECT 'bookings (would orphan)',       count(*) FROM booking_schema.booking      WHERE customer_id IN (SELECT id FROM targets)
UNION ALL SELECT 'support tickets (would orphan)', count(*) FROM admin_schema.support_ticket WHERE raised_by_id IN (SELECT id FROM targets)
UNION ALL SELECT 'wallet (would orphan)',         count(*) FROM rewards_schema.wallet       WHERE user_id IN (SELECT id FROM targets)
ORDER BY 2 DESC, 1;

BEGIN;

/*
 * Order matters: children before parents, and the FK-backed tables must go first or the final
 * DELETE is rejected. otp_requests has no FK but keys on PHONE rather than user id, so it is
 * cleared by number — leaving those behind would let a stale code be verified against a phone
 * that no longer has an account.
 */

DELETE FROM user_schema.refresh_tokens
 WHERE user_id IN (SELECT id FROM user_schema.users WHERE phone IN ('+919113639755','+919663831388'));

DELETE FROM user_schema.onboarding_state
 WHERE user_id IN (SELECT id FROM user_schema.users WHERE phone IN ('+919113639755','+919663831388'));

DELETE FROM user_schema.user_roles
 WHERE user_id IN (SELECT id FROM user_schema.users WHERE phone IN ('+919113639755','+919663831388'));

-- Keyed by phone, not user id. See the note above.
DELETE FROM user_schema.otp_requests
 WHERE phone IN ('+919113639755','+919663831388');

-- Salon staff seats. These are logical refs — nothing would stop the row surviving its user.
DELETE FROM salon_schema.salon_staff
 WHERE user_id IN (SELECT id FROM user_schema.users WHERE phone IN ('+919113639755','+919663831388'));

/*
 * The account itself, last.
 *
 * Deliberately matched on the exact E.164 strings rather than a LIKE on the last ten digits: a
 * DELETE is not the place for a fuzzy match. If canonicalisation ever stored one of these
 * differently, this finds nothing and deletes nothing, which is the correct failure.
 */
DELETE FROM user_schema.users
 WHERE phone IN ('+919113639755','+919663831388');

COMMIT;

\echo ''
\echo '=== AFTER: both should be gone ================================================'
\echo ''

SELECT phone, COALESCE(name,'(no name)') AS name, email, default_role
FROM user_schema.users
ORDER BY created_at DESC;

\echo ''
\echo 'If the two numbers no longer appear above, the delete succeeded.'
\echo 'nidhitrikani50401@gmail.com on +918431710204 is left alone — it was not in scope.'
\echo ''
