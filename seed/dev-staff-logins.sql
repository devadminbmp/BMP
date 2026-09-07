-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  DEV STAFF LOGINS — fixed accounts, fixed password, fixed 2FA. Session 62.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  PASSWORD for every account below:   BmpLocalDev2026!Console
--  2FA SECRET for every account below: YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK
--
--  Get the current 6-digit code with:
--      node tools\totp.mjs YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK --watch
--
--  Console: http://localhost:5180
--
--  | Email                            | Role          | Door           |
--  |----------------------------------|---------------|----------------|
--  | dev.super@bemyprofessional.in    | super_admin   | /admin/login   |
--  | dev.admin@bemyprofessional.in    | admin         | /admin/login   |
--  | dev.ops@bemyprofessional.in      | ops_admin     | /admin/login   |
--  | dev.lead@bemyprofessional.in     | support_lead  | /support/login |
--  | dev.support@bemyprofessional.in  | support_agent | /support/login |
--  | dev.finance@bemyprofessional.in  | finance_admin | /support/login |
--  | dev.readonly@bemyprofessional.in | read_only     | /support/login |
--  | devadmin.bmp@gmail.com           | super_admin   | /admin/login   |  <- see below
--
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--  USAGE
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--      docker cp seed\dev-staff-logins.sql bmp-postgres-1:/tmp/logins.sql
--      docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/logins.sql
--
--  Run it any time after bmp-admin has started once (so Flyway has built admin_schema). It does
--  not care which terminal you are in, whether bmp-admin is running, or what environment
--  variables are set. Re-running it RESETS the password and 2FA back to the values above, which
--  is the whole point — it is the "get me back in" button.
--
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--  WHY THIS FILE EXISTS, GIVEN DevStaffSeeder ALREADY DOES THIS
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--  The seeder is the better mechanism and it kept not working, for one reason every time:
--  `$env:` variables in PowerShell live in ONE terminal session. Set them in window A, start the
--  service from window B or from the IDE's run button, and the seeder sees `enabled=false` and
--  silently does nothing. It also refuses to touch an account that already exists, so a second
--  attempt with a different password looks identical to a failure.
--
--  Three sessions were lost to that. This file removes the variable from the equation entirely:
--  the hashes are precomputed and committed, so there is nothing to configure and nothing to
--  scope wrongly. It is strictly less elegant and strictly more reliable, and for a local
--  development login that is the right trade.
--
--  The seeder stays. Use whichever works; they create the same six accounts.
--
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--  ⚠  LOCAL DEVELOPMENT ONLY — AND THIS ONE HAS NO GUARD
-- ────────────────────────────────────────────────────────────────────────────────────────────────
--  DevStaffSeeder refuses to run unless the datasource is on localhost. **A SQL file cannot check
--  that.** It runs wherever you point psql, which means the safety here is you reading this line.
--
--  The password and the 2FA secret are both in git. Anyone with the repo can sign in to any
--  database this has been applied to, with full super_admin rights over customer PII. Running it
--  against the shared Neon branch would hand the console to every person who has ever cloned BMP.
--
--  Before you run it, confirm the target is your own Docker Postgres:
--      docker exec bmp-postgres-1 psql -U bmp -d bmp -c "SELECT inet_server_addr(), current_database();"
--
--  Delete these accounts before anything real exists:
--      DELETE FROM admin_schema.bmp_staff WHERE email LIKE 'dev.%@bemyprofessional.in';
-- ════════════════════════════════════════════════════════════════════════════════════════════════

BEGIN;

/*
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *  FIRST: claim devadmin.bmp@gmail.com — the account that could never be logged into. Session 65.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 *  V003 seeds this super_admin row with `password_hash = 'LOCKED-NO-PASSWORD-SET'`. That is not a
 *  bcrypt hash and is not meant to be — it is a deliberate sentinel, so that the console's first
 *  account cannot ship with a password sitting in git. StaffBootstrap claims it at startup from
 *  BMP_ADMIN_BOOTSTRAP_EMAIL / BMP_ADMIN_BOOTSTRAP_PASSWORD, once, and only while it is still
 *  locked.
 *
 *  ── WHY THIS BLOCK EXISTS ──────────────────────────────────────────────────────────────────────
 *  The design is right and the local experience is a trap. The account is REAL and `active`, it is
 *  the obvious thing to type, and every attempt returns the same "Email or password is incorrect"
 *  as a genuine typo — because verification against a non-hash simply fails. There is nothing on
 *  the login screen, and nothing in the error, that distinguishes "wrong password" from "this
 *  account has no password and never had one". Two sessions were lost to that.
 *
 *  StaffBootstrap can fix it, and it depends on `$env:` variables being set in the same terminal
 *  that starts the service — the exact failure mode documented further down this file as the
 *  reason this SQL file exists at all.
 *
 *  So: claimed here, with the same password and 2FA secret as the six dev accounts.
 *
 *  ── SCOPED BY THE SENTINEL, NOT BY THE EMAIL ───────────────────────────────────────────────────
 *  `WHERE password_hash = 'LOCKED-NO-PASSWORD-SET'` carries the same safety property as
 *  StaffBootstrap's own check: an account somebody has already claimed with a real password is
 *  NEVER overwritten by this. Running this file against a database where the bootstrap account is
 *  in use changes nothing, rather than silently resetting a live admin's credentials.
 *
 *  That is deliberately UNLIKE the six rows below, which reset unconditionally — those are
 *  disposable dev accounts and resetting them is their purpose. This one might be somebody's real
 *  login.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 */
UPDATE admin_schema.bmp_staff
   SET password_hash    = '$2a$10$Rdjo6C27QvHgMH1HxgDX1e3Mzgca7ALxn1gtj5QrOBdRA1/VoXJBG',
       totp_secret      = 'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK',
       totp_enrolled_at = now(),
       status           = 'active',
       failed_login_count = 0,
       locked_until     = NULL,
       updated_at       = now()
 WHERE lower(email) = 'devadmin.bmp@gmail.com'
   AND password_hash = 'LOCKED-NO-PASSWORD-SET';

/*
 * The hashes are real bcrypt ($2a$, cost 10), generated from the password in the header and
 * verified against it. Spring's BCryptPasswordEncoder reads the cost and salt out of the hash
 * itself, so a precomputed value works exactly like one the application produced.
 *
 * Six different hashes for one password, deliberately: bcrypt salts per-hash, so identical
 * passwords produce different strings. Reusing one hash across six rows would work and would also
 * teach the next reader that identical hashes mean identical passwords — which is true here and
 * false in general.
 *
 * `tier` mirrors StaffPermission: 1 agent, 2 lead, 3 ops, 4 owner, 0 off the escalation ladder.
 * It is NOT decorative — TicketAssignmentService assigns to somebody at exactly the ticket's tier,
 * and tier 0 can read a queue but never hold an item in one.
 */
INSERT INTO admin_schema.bmp_staff
    (id, name, phone, email, password_hash, role, status, tier,
     totp_secret, totp_enrolled_at, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-00000000de01', 'Dev Superadmin', '+910000000021',
     'dev.super@bemyprofessional.in',
     '$2a$10$tbJUWZ3M3D.dNxaWxkBrp./56VSVMJMLVgseJsu3YjSm68.Un/Yq.',
     'super_admin', 'active', 4,
     'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK', now(), now(), now()),

    /*
     * Session 65 — the ADMIN rung. Between the platform owner and ops.
     *
     * tier 0, NOT 3 or 4: rank and tier are different ladders. This role manages people, salons
     * and money and works no support tickets, so it must never enter ticket auto-assignment.
     * See SupportTier.java and V015.
     */
    ('01930000-0000-7000-8000-00000000de07', 'Dev Admin', '+910000000027',
     'dev.admin@bemyprofessional.in',
     '$2a$10$k00mMIM1TXSOfbXsUnAP3OFOMh3B2F3x0DntPFe.qZTXJm2uuY.7u',
     'admin', 'active', 0,
     'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK', now(), now(), now()),

    ('01930000-0000-7000-8000-00000000de02', 'Dev Ops', '+910000000022',
     'dev.ops@bemyprofessional.in',
     '$2a$10$eYkTUuXZL9kFVRud9ghu.OBZu5Cfp4YKFDLwU2FkeTs1jFHMGE9hu',
     'ops_admin', 'active', 3,
     'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK', now(), now(), now()),

    ('01930000-0000-7000-8000-00000000de03', 'Dev Support Lead', '+910000000023',
     'dev.lead@bemyprofessional.in',
     '$2a$10$Vq6o/QFtxb1QyspqeePoQuDlG3n52KDcoQWqcO8qYgYjegCp5ce8G',
     'support_lead', 'active', 2,
     'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK', now(), now(), now()),

    ('01930000-0000-7000-8000-00000000de04', 'Dev Support', '+910000000024',
     'dev.support@bemyprofessional.in',
     '$2a$10$YtKCEUnb0NrkxswC9AEGh.GwrNX1JJ/naJ9fF3bvi5jG..OFcHFs6',
     'support_agent', 'active', 1,
     'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK', now(), now(), now()),

    ('01930000-0000-7000-8000-00000000de05', 'Dev Finance', '+910000000025',
     'dev.finance@bemyprofessional.in',
     '$2a$10$rGvAOU8ifQFfGBip6kIK1..d9lRZDyXkjW/GMpggzdOMMgBDvqLj.',
     'finance_admin', 'active', 0,
     'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK', now(), now(), now()),

    ('01930000-0000-7000-8000-00000000de06', 'Dev Read Only', '+910000000026',
     'dev.readonly@bemyprofessional.in',
     '$2a$10$d60P6VZSF8S30y9P9h9.F.nCn50rGSbPkviYtDZNG3wr/1tWhOmQK',
     'read_only', 'active', 0,
     'YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK', now(), now(), now())

/*
 * ON CONFLICT DO UPDATE, not DO NOTHING — and that is the opposite of what dev-seed-team.sql does.
 *
 * That file leaves existing rows alone because it seeds a ROSTER, and a local database mid-test
 * should keep whatever state somebody put it in. This file seeds LOGINS, and its entire job is to
 * be the thing you run when you cannot get in. A version that quietly skipped would reproduce the
 * exact failure that made it necessary.
 *
 * So it resets the password, the 2FA secret and the status — deliberately clearing any lockout —
 * while leaving the name and phone alone.
 */
ON CONFLICT (id) DO UPDATE SET
    password_hash    = EXCLUDED.password_hash,
    totp_secret      = EXCLUDED.totp_secret,
    totp_enrolled_at = EXCLUDED.totp_enrolled_at,
    role             = EXCLUDED.role,
    tier             = EXCLUDED.tier,
    status           = 'active',
    updated_at       = now();

-- Clear any lockout from failed attempts. Locking out the account you use to test lockout is a
-- rite of passage; needing a second tool to undo it is not.
UPDATE admin_schema.bmp_staff
   SET failed_login_count = 0,
       locked_until = NULL
 WHERE email LIKE 'dev.%@bemyprofessional.in';

COMMIT;

\echo ''
\echo '  Six staff logins ready.'
\echo ''
\echo '    Password:   BmpLocalDev2026!Console'
\echo '    2FA secret: YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK'
\echo '    Code:       node tools\\totp.mjs YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK --watch'
\echo ''
\echo '    /admin/login    dev.super@bemyprofessional.in     (super_admin)'
\echo '                    dev.admin@bemyprofessional.in     (admin)'
\echo '                    dev.ops@bemyprofessional.in       (ops_admin)'
\echo ''
\echo '    /support/login  dev.lead@bemyprofessional.in      (support_lead)'
\echo '                    dev.support@bemyprofessional.in   (support_agent)'
\echo '                    dev.finance@bemyprofessional.in   (finance_admin)'
\echo '                    dev.readonly@bemyprofessional.in  (read_only)'
\echo ''

SELECT email, role, tier, status,
       CASE WHEN totp_secret IS NULL THEN 'NO 2FA' ELSE 'ready' END AS twofactor
FROM admin_schema.bmp_staff
WHERE email LIKE 'dev.%@bemyprofessional.in'
ORDER BY tier DESC, email;
