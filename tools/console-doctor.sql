-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  CONSOLE DOCTOR — why can't I sign in, and where is my salon request? Session 64.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- WHY THIS EXISTS
-- ────────────────────────────────────────────────────────────────────────────────────────────────
-- The console's sign-in failure message is DELIBERATELY IDENTICAL for six different causes:
--
--     wrong password · no such account · account locked · status = invited
--     status = suspended · status = offboarded
--
-- That is not laziness, it is the design (StaffAuthService.GENERIC_FAILURE). A message that said
-- "wrong password" would confirm the email exists, and one that said "account locked" would tell an
-- attacker their guessing is working. The cost of that choice is that the person who is legitimately
-- stuck cannot tell WHICH of the six they are — so the diagnosis has to happen here, against the
-- database, where the caller is already trusted.
--
-- The salon queue has the mirror-image problem: an empty queue looks the same whether nothing was
-- submitted, the submission never reached bmp-salon, or the review row was never created.
--
-- USAGE
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--   docker cp tools\console-doctor.sql bmp-postgres-1:/tmp/console-doctor.sql
--   docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/console-doctor.sql
--
-- Read-only. Safe to run any time, against a local database.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\echo ''
\echo '=== 1. Staff accounts — do they exist, and can they sign in RIGHT NOW? ====='
\echo 'NO ROWS AT ALL     -> you have never run seed/dev-staff-logins.sql.'
\echo 'can_sign_in = NO   -> read the reason column. This is the 401 you are seeing.'
\echo 'locked_for         -> the account is locked; it clears itself after 15 minutes,'
\echo '                      or immediately if you re-run seed/dev-staff-logins.sql.'
\echo ''

SELECT
    email,
    role,
    status,
    failed_login_count                                        AS fails,
    CASE WHEN locked_until IS NULL OR locked_until < now() THEN '—'
         ELSE to_char(locked_until - now(), 'MI:SS') END      AS locked_for,
    CASE WHEN totp_secret IS NULL THEN 'not enrolled' ELSE 'enrolled' END AS twofactor,
    CASE
        WHEN status <> 'active'                            THEN 'NO — status is ' || status
        WHEN locked_until IS NOT NULL
             AND locked_until > now()                      THEN 'NO — LOCKED OUT (' || failed_login_count || ' failed attempts)'
        ELSE 'yes'
    END                                                       AS can_sign_in,
    last_login_at
FROM admin_schema.bmp_staff
ORDER BY (locked_until > now()) DESC NULLS LAST, email;

\echo ''
\echo '=== 2. Salons — did the signup actually reach bmp-salon? =================='
\echo 'EMPTY  -> the salon was never created. The signup failed before the database;'
\echo '          check the bmp-salon log and the browser network tab for the POST.'
\echo 'status = pending -> created and waiting. It SHOULD appear in the console queue.'
\echo ''

SELECT
    id,
    COALESCE(reference, '(none)') AS reference,
    name,
    status,
    COALESCE(area, '—')           AS area,
    created_at
FROM salon_schema.salon
ORDER BY created_at DESC
LIMIT 10;

\echo ''
\echo '=== 3. Review rows — is it actually IN the moderation queue? ==============='
\echo 'A salon is invisible to the console until a salon_review row exists. bmp-salon'
\echo 'creates it by calling bmp-admin at signup, and that call is BEST-EFFORT: if'
\echo 'bmp-admin was down, signup still succeeded and the queue never heard about it.'
\echo ''
\echo 'SalonModerationService.reconcileMissingReviews() repairs this every time the'
\echo 'queue is loaded — but only if bmp-admin can reach bmp-salon through Eureka.'
\echo 'So: rows in section 2 with nothing here means EITHER you have not opened the'
\echo 'queue since both services came up, OR that Feign call is failing. Check the'
\echo 'bmp-admin log for "Could not reconcile pending salons".'
\echo ''

SELECT
    s.name                                   AS salon,
    s.status                                 AS salon_status,
    COALESCE(r.status, '*** NO REVIEW ROW — INVISIBLE TO THE CONSOLE ***') AS review_status,
    r.submitted_at,
    r.decided_at,
    COALESCE(r.decision_note, '—')           AS decision_note
FROM salon_schema.salon s
LEFT JOIN admin_schema.salon_review r ON r.salon_id = s.id
ORDER BY s.created_at DESC
LIMIT 10;

\echo ''
\echo '=== 4. Recent lockouts and staff actions =================================='
\echo 'STAFF_LOCKED here confirms section 1. Note that a wrong TWO-FACTOR code counts'
\echo 'toward the same five-strike lockout as a wrong password — so reading a stale'
\echo 'code off the authenticator a few times is enough to lock yourself out.'
\echo ''

SELECT action, actor_email, created_at
FROM admin_schema.audit_log
WHERE action IN ('STAFF_LOCKED', 'STAFF_LOGIN', 'TOTP_ENROLLED')
ORDER BY created_at DESC
LIMIT 10;

\echo ''
\echo '=== READ IT LIKE THIS ====================================================='
\echo '  1 empty                  -> run seed/dev-staff-logins.sql'
\echo '  1 says LOCKED OUT        -> wait 15 min, or re-run that seed file to clear it'
\echo '  1 says status is invited -> the activation code was never redeemed'
\echo '  2 empty                  -> the salon signup never reached bmp-salon at all'
\echo '  2 has rows, 3 says NO REVIEW ROW -> open the console queue once with both'
\echo '     bmp-admin and bmp-salon running; it self-heals. If it does not, the Feign'
\echo '     call is failing — check Eureka and the bmp-admin log.'
\echo ''
