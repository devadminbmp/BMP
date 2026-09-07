-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  STAFF LOGIN DOCTOR — why can't this console account sign in? Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  ── WHY THIS EXISTS ────────────────────────────────────────────────────────────────────────────
--  The console returns the SAME "Email or password is incorrect" for every failure, on purpose:
--  a login screen that distinguishes "no such account" from "wrong password" tells an attacker
--  which addresses are real. That is the right trade for the screen and a terrible one for
--  debugging — three different causes were mistaken for a typo across two sessions.
--
--  This file is inside the trust boundary, so it can say what the screen must not.
--
--  ── USAGE ──────────────────────────────────────────────────────────────────────────────────────
--      docker cp tools\staff-login-doctor.sql bmp-postgres-1:/tmp/doctor.sql
--      docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/doctor.sql
--
--  Read-only. Safe any time. Reveals no password — only the SHAPE of the stored hash, which is
--  what distinguishes the causes.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

\echo ''
\echo '=== EVERY CONSOLE ACCOUNT, AND WHETHER IT CAN SIGN IN ========================='
\echo ''

SELECT
    email,
    role,
    status,
    /*
     * The whole diagnosis is in the SHAPE of password_hash. Three states that look identical from
     * the login screen and mean completely different things:
     *
     *   LOCKED-NO-PASSWORD-SET  V003 seeded it and nobody ever claimed it. NO password works.
     *                           This is what devadmin.bmp@gmail.com is until it is claimed.
     *   $2a$/$2b$…              a real bcrypt hash. A password exists; if yours is refused, it is
     *                           the wrong one — or the seed set a different one than you expect.
     *   anything else           somebody wrote a non-hash into the column by hand.
     */
    CASE
        WHEN password_hash = 'LOCKED-NO-PASSWORD-SET'
            THEN 'NEVER CLAIMED — no password will ever work'
        WHEN password_hash LIKE '$2a$%' OR password_hash LIKE '$2b$%' OR password_hash LIKE '$2y$%'
            THEN 'has a real password'
        ELSE 'BROKEN — not a bcrypt hash: ' || left(password_hash, 24)
    END AS password_state,

    CASE WHEN totp_secret IS NULL THEN 'not enrolled — first login will set it up'
         ELSE 'enrolled' END AS two_factor,

    -- Both of these produce a 401 that looks exactly like a wrong password.
    CASE WHEN status <> 'active' THEN 'BLOCKED BY STATUS (' || status || ')' ELSE 'ok' END AS status_check,
    CASE WHEN locked_until IS NOT NULL AND locked_until > now()
            THEN 'LOCKED OUT until ' || to_char(locked_until, 'HH24:MI:SS')
         ELSE 'not locked' END AS lockout,
    failed_login_count AS failed_attempts
FROM admin_schema.bmp_staff
ORDER BY
    CASE role WHEN 'super_admin' THEN 1 WHEN 'ops_admin' THEN 2 WHEN 'support_lead' THEN 3
              WHEN 'support_agent' THEN 4 ELSE 5 END,
    email;

\echo ''
\echo '=== HOW TO READ IT ============================================================'
\echo ''
\echo '  "NEVER CLAIMED"          -> the account is real but has no password. Run'
\echo '                              seed/dev-staff-logins.sql, which claims it. Nothing you type'
\echo '                              can work until then.'
\echo ''
\echo '  "has a real password"    -> a password exists. If BmpLocalDev2026!Console is refused, the'
\echo '                              account was claimed with a DIFFERENT one (StaffBootstrap, or'
\echo '                              somebody set it). seed/dev-staff-logins.sql deliberately will'
\echo '                              NOT overwrite it — see the note in that file. Use the reset'
\echo '                              below if this is your own local database.'
\echo ''
\echo '  "BLOCKED BY STATUS"      -> invited/suspended/offboarded. The password is irrelevant;'
\echo '                              the login is refused before it is checked.'
\echo ''
\echo '  "LOCKED OUT until ..."   -> too many wrong attempts. Wait, or clear it with the reset.'
\echo ''
\echo '  no rows at all           -> bmp-admin has never started against this database, so Flyway'
\echo '                              has not built admin_schema. Start it once, then seed.'
\echo ''
\echo '=== FORCE-RESET A LOCAL DEV ACCOUNT ==========================================='
\echo ''
\echo 'Only for a database you own. It sets the password to BmpLocalDev2026!Console and clears'
\echo 'any lockout, whatever state the account was in:'
\echo ''
\echo '  UPDATE admin_schema.bmp_staff'
\echo '     SET password_hash = ''$2a$10$Rdjo6C27QvHgMH1HxgDX1e3Mzgca7ALxn1gtj5QrOBdRA1/VoXJBG'','
\echo '         totp_secret = ''YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK'', totp_enrolled_at = now(),'
\echo '         status = ''active'', failed_login_count = 0, locked_until = NULL, updated_at = now()'
\echo '   WHERE lower(email) = ''devadmin.bmp@gmail.com'';'
\echo ''
\echo 'Then get the 6-digit code with:  node tools\totp.mjs YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK --watch'
\echo ''
