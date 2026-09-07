-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--  BMP DEV SEED — the team, their leave, and some goodwill history. Sessions 59–60.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- WHY THIS FILE EXISTS
-- ───────────────────────────────────────────────────────────────────────────────────────────────
-- `docker compose down -v` wipes the database, and it has to be wiped whenever a migration
-- changes. Anything entered by hand goes with it. Sessions 59 and 60 added five screens — Team,
-- Leave, Queues, Goodwill and the approvals matrix — and on a fresh database every one of them is
-- empty, which is indistinguishable from broken. A reviewer opening the console after a reset
-- concludes the feature does not work.
--
-- USAGE (from the BMP repo root, containers up, services having run once so Flyway has built the
--        schemas). PowerShell: `<` is a reserved operator there and the redirect form fails before
--        Docker runs, hence `docker cp` first:
--
--   docker cp seed\dev-seed-team.sql bmp-postgres-1:/tmp/dev-seed-team.sql
--   docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/dev-seed-team.sql
--
-- Run AFTER dev-seed.sql. NEVER against a real database.
--
-- IDS
-- ───────────────────────────────────────────────────────────────────────────────────────────────
-- Fixed, readable UUIDs rather than gen_random_uuid(): re-runs must produce the same rows, two
-- developers' databases should be diffable, and nothing here needs pgcrypto. Same convention as
-- V010's seeded matrix.
--
-- DATES ARE RELATIVE
-- ───────────────────────────────────────────────────────────────────────────────────────────────
-- Leave is seeded relative to CURRENT_DATE, not as literals. Fixed dates rot: a "who's off today"
-- panel seeded with 2026-08-28 shows nobody from the 29th onward, and the next person to look
-- concludes the panel is broken rather than that the seed is stale.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════

BEGIN;


-- ── 0. THE COLLEAGUES ──────────────────────────────────────────────────────────────────────────
--
-- WHY THIS SECTION HAD TO BE ADDED
-- ───────────────────────────────────────────────────────────────────────────────────────────────
-- The first version of this file assumed a populated staff table and guarded every insert with
-- `WHERE EXISTS (... role = 'support_agent')`. V003 seeds exactly ONE staff row — the superadmin —
-- so on a real dev database every guard was false and the file inserted nothing, silently. It
-- produced precisely the empty screens it was written to prevent, and reported success while doing
-- it. Caught by running it against a real PostgreSQL rather than reading it.
--
-- Worth stating as a rule: a seed guarded on data it does not create is a seed that does nothing.
--
-- PASSWORDS
-- ───────────────────────────────────────────────────────────────────────────────────────────────
-- Every row below carries the same non-bcrypt placeholder V003 uses for the superadmin. It is not a
-- weak password — it is not a hash at all, so verification cannot succeed and none of these
-- accounts can be signed into. They exist to populate a roster, a queue and an approvals ladder.
--
-- To actually use one, issue an activation code through the console (Staff accounts → reissue),
-- which is the same path a real hire goes through.

INSERT INTO admin_schema.bmp_staff
    (id, name, phone, email, password_hash, role, status, tier, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-000000000002', 'Arjun Bhat',  '+910000000002',
     'arjun.dev@bemyprofessional.in',  'LOCKED-NO-PASSWORD-SET', 'support_agent', 'active', 1,
     NOW() - INTERVAL '8 months', NOW()),
    ('01930000-0000-7000-8000-000000000003', 'Nisha Verma', '+910000000003',
     'nisha.dev@bemyprofessional.in',  'LOCKED-NO-PASSWORD-SET', 'support_agent', 'active', 1,
     NOW() - INTERVAL '5 months', NOW()),
    ('01930000-0000-7000-8000-000000000004', 'Priya Rao',   '+910000000004',
     'priya.dev@bemyprofessional.in',  'LOCKED-NO-PASSWORD-SET', 'support_lead',  'active', 2,
     NOW() - INTERVAL '14 months', NOW()),
    ('01930000-0000-7000-8000-000000000005', 'Rahul Menon', '+910000000005',
     'rahul.dev@bemyprofessional.in',  'LOCKED-NO-PASSWORD-SET', 'ops_admin',     'active', 3,
     NOW() - INTERVAL '18 months', NOW()),
    ('01930000-0000-7000-8000-000000000006', 'Kavya Iyer',  '+910000000006',
     'kavya.dev@bemyprofessional.in',  'LOCKED-NO-PASSWORD-SET', 'finance_admin', 'active', 0,
     NOW() - INTERVAL '10 months', NOW())
ON CONFLICT (id) DO NOTHING;

-- ── 1. EMPLOYMENT DETAILS ON THE EXISTING STAFF ────────────────────────────────────────────────
--
-- An UPDATE, not an INSERT: the rows exist by now (V003's superadmin plus section 0), and inserting
-- again on a re-run would produce two "Priya"s and a login that resolves to whichever one the query
-- happened to return first.

UPDATE admin_schema.bmp_staff
   SET job_title  = CASE role
                      WHEN 'super_admin'   THEN 'Founder'
                      WHEN 'ops_admin'     THEN 'Operations lead'
                      WHEN 'support_lead'  THEN 'Support lead'
                      WHEN 'support_agent' THEN 'Support specialist'
                      WHEN 'finance_admin' THEN 'Finance'
                      ELSE job_title
                    END,
       shift_note = CASE role
                      WHEN 'support_agent' THEN 'Mon–Sat, 10am–7pm'
                      WHEN 'support_lead'  THEN 'Mon–Fri, 11am–8pm'
                      ELSE shift_note
                    END,
       joined_on  = COALESCE(joined_on, CURRENT_DATE - INTERVAL '8 months')
 WHERE job_title IS NULL OR joined_on IS NULL;

-- Employee codes, one per row, in a stable order so a re-run assigns the same code to the same
-- person. Without ORDER BY the numbering would shuffle between runs and stop being an identifier.
WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS n
      FROM admin_schema.bmp_staff
     WHERE employee_code IS NULL
)
UPDATE admin_schema.bmp_staff s
   SET employee_code = 'BMP-' || LPAD(numbered.n::text, 3, '0')
  FROM numbered
 WHERE s.id = numbered.id;

/*
 * The org chart: everyone below the owner reports to the owner.
 *
 * Deliberately flat rather than inventing a hierarchy. A seeded chart that says an agent reports to
 * a lead who reports to ops looks like a real structure and is fiction — and TeamController's cycle
 * detection is worth exercising against something honest. One level is enough to prove the field
 * renders and the picker works.
 */
UPDATE admin_schema.bmp_staff
   SET reports_to_staff_id = (SELECT id FROM admin_schema.bmp_staff WHERE role = 'super_admin' LIMIT 1)
 WHERE role <> 'super_admin'
   AND reports_to_staff_id IS NULL
   AND EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'super_admin');

-- ── 2. LEAVE ───────────────────────────────────────────────────────────────────────────────────
--
-- Four rows, chosen to make each state visible on the screen at once: somebody off TODAY (so the
-- "off today" panel is populated and the roster shows an "On leave" badge), somebody's request
-- awaiting a decision (so the approve/refuse controls appear for ops), a half day (the V011
-- constraint that only allows it on a single date), and a past approved one.

INSERT INTO admin_schema.staff_leave
    (id, staff_id, leave_type, starts_on, ends_on, half_day, reason, status,
     decided_by_staff_id, decided_at, created_at)
SELECT
    'b1b1b1b1-0000-4000-8000-000000000001',
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'support_agent' ORDER BY created_at LIMIT 1),
    'casual', CURRENT_DATE, CURRENT_DATE + 1, NULL,
    'Family function.', 'approved',
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'ops_admin' ORDER BY created_at LIMIT 1),
    now(), now() - INTERVAL '3 days'
WHERE EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'support_agent')
  AND EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'ops_admin')
ON CONFLICT (id) DO NOTHING;

-- Pending: gives ops something to decide, which is the only way to see that half of the screen.
INSERT INTO admin_schema.staff_leave
    (id, staff_id, leave_type, starts_on, ends_on, half_day, reason, status, created_at)
SELECT
    'b1b1b1b1-0000-4000-8000-000000000002',
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'support_lead' ORDER BY created_at LIMIT 1),
    'casual', CURRENT_DATE + 10, CURRENT_DATE + 12, NULL,
    'Short holiday — booked in advance.', 'pending', now() - INTERVAL '1 day'
WHERE EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'support_lead')
ON CONFLICT (id) DO NOTHING;

-- Half day. Single date, as chk_leave_half_single_day requires.
INSERT INTO admin_schema.staff_leave
    (id, staff_id, leave_type, starts_on, ends_on, half_day, reason, status, created_at)
SELECT
    'b1b1b1b1-0000-4000-8000-000000000003',
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'finance_admin' ORDER BY created_at LIMIT 1),
    'casual', CURRENT_DATE + 4, CURRENT_DATE + 4, 'afternoon',
    'Dentist.', 'pending', now()
WHERE EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'finance_admin')
ON CONFLICT (id) DO NOTHING;

-- Past, taken. So "My leave" has history rather than one row.
INSERT INTO admin_schema.staff_leave
    (id, staff_id, leave_type, starts_on, ends_on, half_day, reason, status,
     decided_by_staff_id, decided_at, created_at)
SELECT
    'b1b1b1b1-0000-4000-8000-000000000004',
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'support_agent' ORDER BY created_at LIMIT 1),
    'sick', CURRENT_DATE - 21, CURRENT_DATE - 20, NULL,
    'Fever.', 'approved',
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'ops_admin' ORDER BY created_at LIMIT 1),
    now() - INTERVAL '20 days', now() - INTERVAL '21 days'
WHERE EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'support_agent')
  AND EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'ops_admin')
ON CONFLICT (id) DO NOTHING;

/*
 * Make the seeded state TRUE, not just recorded.
 *
 * Approved leave is supposed to take somebody out of the ticket rotation — LeaveRotationJob does
 * that at 00:05. Seeding an approved row without also clearing the flag would leave the roster
 * saying "On leave" beside a person who is still being assigned tickets, which is precisely the
 * inconsistency the feature exists to prevent, demonstrated in the demo data.
 */
UPDATE admin_schema.bmp_staff
   SET accepting_tickets = false
 WHERE id IN (
    SELECT staff_id FROM admin_schema.staff_leave
     WHERE status = 'approved' AND CURRENT_DATE BETWEEN starts_on AND ends_on
 );

-- ── 3. QUEUE CONFIG ────────────────────────────────────────────────────────────────────────────
--
-- V011 already seeds one 'auto' row per tier. This only sets a load cap on L1, so the Queues tab
-- shows a non-default value and the cap's effect is demonstrable.

UPDATE admin_schema.queue_config
   SET max_open_per_agent = 15
 WHERE tier = 1 AND max_open_per_agent = 0;

-- ── 4. GOODWILL HISTORY ────────────────────────────────────────────────────────────────────────
--
-- Two coupons on one booking, so the ledger has rows and — more usefully — so the "you can offer
-- up to ₹X" hint on the coupon form has something to subtract. A ceiling that always reads "the
-- full amount" never demonstrates the rule it exists for.
--
-- ON THE booking_id BELOW
-- ───────────────────────────────────────────────────────────────────────────────────────────────
-- It is a fixed demo id that does NOT correspond to a seeded booking — dev-seed.sql seeds salons,
-- stylists, services and availability, but no bookings. Said plainly because the obvious assumption
-- is that it lines up with one, and a reader who believes that will waste time looking for it.
--
-- That is harmless here: goodwill_grant has no foreign key (bookings live in another service), and
-- the value of these rows is that the Goodwill ledger has content and the coupon form's ceiling has
-- something to subtract. To see the ceiling in action, paste this id into "Booking this is about".
--
-- The cap itself will still call bmp-booking and refuse, because the booking genuinely does not
-- exist — which is the correct behaviour (see GoodwillCapService: an unverifiable amount is
-- refused, never allowed). Make a real booking through the app to exercise the allow path.

INSERT INTO admin_schema.goodwill_grant
    (id, booking_id, action_type, value_paise, approval_request_id,
     granted_by_staff_id, granted_by_role, reference, created_at)
SELECT
    'c1c1c1c1-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-00000000b001',
    'coupon.issue', 20000, NULL,
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'support_agent' ORDER BY created_at LIMIT 1),
    'support_agent', 'SORRY200', now() - INTERVAL '9 days'
WHERE EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'support_agent')
ON CONFLICT (id) DO NOTHING;

INSERT INTO admin_schema.goodwill_grant
    (id, booking_id, action_type, value_paise, approval_request_id,
     granted_by_staff_id, granted_by_role, reference, created_at)
SELECT
    'c1c1c1c1-0000-4000-8000-000000000002',
    '00000000-0000-4000-8000-00000000b001',
    'coupon.issue', 15000, NULL,
    (SELECT id FROM admin_schema.bmp_staff WHERE role = 'support_lead' ORDER BY created_at LIMIT 1),
    'support_lead', 'SORRY150', now() - INTERVAL '2 days'
WHERE EXISTS (SELECT 1 FROM admin_schema.bmp_staff WHERE role = 'support_lead')
ON CONFLICT (id) DO NOTHING;

COMMIT;

-- ── WHAT YOU SHOULD SEE ────────────────────────────────────────────────────────────────────────
--   Team → Roster    six people — owner, ops, a lead, two agents and finance — each with a title,
--                    a code, a joined date and a manager. Arjun is "On leave" and not receiving
--                    tickets. None of them can sign in; see the note on passwords above.
--   Team → Leave     somebody off today, two requests waiting on ops (one a half day), and history
--   Team → Queues    L1 capped at 15 open per person; every tier on automatic
--   Goodwill         ₹350 given on one booking, by two different people
--   Coupons          pasting 00000000-0000-4000-8000-00000000b001 lists both earlier gestures.
--                    The ceiling itself needs a booking that really exists — make one in the app.
