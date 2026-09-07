-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  IDENTITY DOCTOR — is one phone really one person? Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  WHAT PROMPTED THIS
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  Reported: signing up twice with the same number and two different email addresses, and the OTP
--  going to the FIRST email rather than the one just typed — plus a suspicion of several accounts
--  on one number.
--
--  Those are two separate things and only one of them is a bug:
--
--    · The OTP going to the stored email is CORRECT and deliberate (AuthService.requestOtp:
--      "an existing user's stored email always wins over whatever the request carries"). If the
--      request's email won, anyone could type YOUR phone number with THEIR email address and be
--      sent your login code. That is account takeover in one step. The system was protecting the
--      account.
--
--    · Not being TOLD is the bug. A signup form that quietly behaves as a login, ignores the email
--      you typed, and mails a code somewhere you cannot see is indistinguishable from a broken
--      system — which is exactly how it was reported.
--
--    · Several accounts on one number would be a third, serious thing — and section 1 below is how
--      you find out whether that is real or inferred.
--
--  USAGE
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--    docker cp tools\identity-doctor.sql bmp-postgres-1:/tmp/identity-doctor.sql
--    docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/identity-doctor.sql
--
--  Read-only. Safe to run any time.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\echo ''
\echo '=== 1. Is the UNIQUE constraint on phone actually THERE? ======================'
\echo 'V004 adds uk_users_phone. If this is EMPTY the constraint never applied — most likely'
\echo 'the migration failed because duplicates already existed, and Flyway stopped. That would'
\echo 'make duplicate accounts possible RIGHT NOW.'
\echo ''

SELECT conname AS constraint_name, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'user_schema.users'::regclass
  AND contype IN ('u', 'p')
ORDER BY conname;

\echo ''
\echo '=== 2. Any phone with more than one account? =================================='
\echo 'EMPTY is the expected, healthy answer.'
\echo ''
\echo 'Rows here mean the constraint is missing (see section 1) OR the phone STRINGS differ while'
\echo 'the human number is the same — +918431710204 vs 918431710204 vs 8431710204. A UNIQUE index'
\echo 'compares bytes, not phone numbers, so it cannot stop the second kind.'
\echo ''

-- Grouped on the DIGITS ONLY, and on the last 10 of them, so differently-prefixed spellings of the
-- same Indian mobile collapse together. This is the check the UNIQUE index cannot perform.
SELECT
    RIGHT(regexp_replace(phone, '[^0-9]', '', 'g'), 10) AS same_human_number,
    count(*)                                            AS accounts,
    string_agg(phone, ' | ')                            AS exact_strings_stored,
    string_agg(COALESCE(email, '(no email)'), ' | ')    AS emails,
    string_agg(default_role, ' | ')                     AS roles
FROM user_schema.users
WHERE deactivated_at IS NULL
GROUP BY 1
HAVING count(*) > 1
ORDER BY 2 DESC;

\echo ''
\echo '=== 3. Every account, newest first — the ground truth ========================='
\echo 'One row per account. If you signed up twice and see ONE row, uniqueness held and the'
\echo 'second attempt correctly became a login.'
\echo ''

SELECT
    id,
    phone,
    COALESCE(name, '(no name)')  AS name,
    COALESCE(email, '(no email)') AS email,
    default_role,
    is_verified,
    created_at
FROM user_schema.users
WHERE deactivated_at IS NULL
ORDER BY created_at DESC
LIMIT 20;

\echo ''
\echo '=== 4. Roles held per account ================================================='
\echo 'ONE account can legitimately hold SEVERAL roles — a salon owner who also books as a'
\echo 'customer is one person, one login, two rows here. That is by design and is NOT a duplicate.'
\echo ''
\echo 'What would be wrong: the same role twice for one user, or the same salon twice.'
\echo ''

SELECT
    u.phone,
    COALESCE(u.name, '(no name)') AS name,
    r.role,
    r.salon_id,
    r.created_at
FROM user_schema.users u
JOIN user_schema.user_roles r ON r.user_id = u.id
WHERE u.deactivated_at IS NULL
ORDER BY u.created_at DESC, r.created_at
LIMIT 30;

\echo ''
\echo '=== 5. Duplicate ROLE rows for one account (real corruption) =================='
\echo 'EMPTY is healthy. Rows here mean a signup ran twice and granted the same role twice.'
\echo ''

SELECT user_id, role, COALESCE(salon_id::text, '(none)') AS salon, count(*) AS times
FROM user_schema.user_roles
GROUP BY user_id, role, salon_id
HAVING count(*) > 1
ORDER BY 4 DESC;

\echo ''
\echo '=== 6. Where did the last few OTPs actually GO? ==============================='
\echo 'The addressed_to column is what the code was emailed to. For an existing account this is'
\echo 'the STORED email, never the one typed into the form — deliberately. See the header.'
\echo ''

SELECT
    phone,
    COALESCE(email, '(NULL — undeliverable)') AS addressed_to,
    created_at,
    consumed_at
FROM user_schema.otp_requests
ORDER BY created_at DESC
LIMIT 10;

\echo ''
\echo '=== READ IT LIKE THIS ========================================================='
\echo '  1 empty                    -> the UNIQUE constraint is MISSING. Serious. Fix before launch.'
\echo '  2 empty                    -> one phone = one account. Uniqueness is holding.'
\echo '  2 has rows, 1 has the UK   -> same human number stored in different FORMATS.'
\echo '  3 shows one row per signup -> the second signup correctly became a login, not a duplicate.'
\echo '  4 shows several roles      -> normal for one person who is both an owner and a customer.'
\echo '  5 has rows                 -> a signup granted the same role twice. Real corruption.'
\echo '  6 shows the FIRST email    -> correct: the account email wins, never the typed one.'
\echo ''
