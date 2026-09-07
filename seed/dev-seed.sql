-- ═══════════════════════════════════════════════════════════════════════════════
--  BMP DEV SEED — re-runnable demo data for a local Docker Postgres.
-- ═══════════════════════════════════════════════════════════════════════════════
--
-- WHY: `docker compose down -v` wipes the database (needed whenever migrations change),
-- so anything entered by hand is lost. This file is the durable, version-controlled copy
-- of the demo data — run it after any reset and you're back to a full-looking app.
--
-- It mirrors the frontend's seed dataset (BMP-FE/src/api/mocks/seed.ts) — SAME ids,
-- names and prices — so the app looks identical whether it's reading mocks or the real API.
--
-- USAGE (from the BMP repo root, with containers up and services having run once so
--        Flyway has created the schemas). PowerShell — `<` is a RESERVED OPERATOR there and
--        the redirect form fails before Docker even runs:
--
--   docker cp seed\dev-seed.sql bmp-postgres-1:/tmp/dev-seed.sql
--   docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/dev-seed.sql
--
-- IDEMPOTENT: every insert is ON CONFLICT DO NOTHING, plus the reclaim step below.
-- NEVER run this against a real/staging database.
--
-- NOTE ON IDS: production uses UUIDv7. For seed data we use fixed, readable UUIDs
-- (0000...-sln1 style) so rows can be referenced across files and re-runs.
--
-- ═══════════════════════════════════════════════════════════════════════════════
-- SESSION 43 — WHY THERE IS A DELETE AT THE TOP OF A SEED FILE
-- ═══════════════════════════════════════════════════════════════════════════════
-- This file used to claim it was idempotent because every insert was
-- `ON CONFLICT (id) DO NOTHING`. That is idempotent against RE-RUNNING ITSELF, and
-- nothing else — which is not what the word implies, and not what anyone relies on.
--
-- What actually happened: while testing, the app created accounts on seeded phone
-- numbers (a fail-open user lookup treated an unreachable bmp-user as "no such user"
-- and signed people up; a too-loose phone validator let `+91919876500001` through).
-- Those rows carry RANDOM ids. So the seed's conflict target — `id` — never matched,
-- while the UNIQUE INDEX on `phone` did. Result:
--
--   ERROR: duplicate key value violates unique constraint "uk_users_phone"
--
-- and because the whole file is one transaction, EVERY subsequent statement failed with
-- "current transaction is aborted" and the lot rolled back. One stale row, and the seed
-- silently does nothing at all. The single-row failure was recoverable; the all-or-nothing
-- rollback is what turned it into "the database is empty and I don't know why".
--
-- The fix has to run BEFORE the inserts, and it has to be a delete: a seeded phone must
-- belong to its seeded id, because everything downstream in this file (salon_staff,
-- bookings, reviews) references those fixed ids. Leaving a squatter row and skipping the
-- insert would produce a database that looks seeded and isn't wired up.
--
-- SCOPE: it only touches rows whose phone is one of the six seeded numbers AND whose id
-- is not the seeded id. A real account can never match — no real user has those numbers.
-- Real signups you made on other numbers are untouched.
-- ═══════════════════════════════════════════════════════════════════════════════

BEGIN;

-- ─────────────────────────────────────────── reclaim squatted seed phones ────────
-- Children first: user_roles / refresh_tokens / onboarding_state have real FKs to
-- users.id, so deleting the parent alone would fail. otp_requests has no FK (a code can
-- be requested before any user row exists) and is keyed by phone, not id.
CREATE TEMP TABLE seed_phone_squatters ON COMMIT DROP AS
SELECT u.id, u.phone
FROM user_schema.users u
JOIN (VALUES
        ('+919876500001', '00000000-0000-7000-0000-000000000001'::uuid),
        ('+919876500002', '00000000-0000-7000-0000-000000000002'::uuid),
        ('+919876500003', '00000000-0000-7000-0000-000000000003'::uuid),
        ('+919876500004', '00000000-0000-7000-0000-000000000004'::uuid),
        ('+919876500005', '00000000-0000-7000-0000-000000000005'::uuid),
        ('+919876500006', '00000000-0000-7000-0000-000000000006'::uuid)
     ) AS seeded(phone, id) ON seeded.phone = u.phone
WHERE u.id <> seeded.id;

DELETE FROM user_schema.user_roles       WHERE user_id IN (SELECT id FROM seed_phone_squatters);
DELETE FROM user_schema.refresh_tokens   WHERE user_id IN (SELECT id FROM seed_phone_squatters);
DELETE FROM user_schema.onboarding_state WHERE user_id IN (SELECT id FROM seed_phone_squatters);
DELETE FROM user_schema.otp_requests     WHERE phone   IN (SELECT phone FROM seed_phone_squatters);
DELETE FROM user_schema.users            WHERE id      IN (SELECT id FROM seed_phone_squatters);

-- ───────────────────────────────────────────────────────────────── users ────────
-- Phone is the identity key (E.164). All seeded users are pre-verified.
INSERT INTO user_schema.users (id, phone, name, gender, age, email, default_role, is_verified, created_at, updated_at)
VALUES
  ('00000000-0000-7000-0000-000000000001', '+919876500001', 'Priya Sharma',  'female', 29, 'priya.customer@example.com',   'customer',    true, now(), now()),
  ('00000000-0000-7000-0000-000000000002', '+919876500002', 'Arjun Mehta',   'male',   34, 'arjun.customer@example.com',   'customer',    true, now(), now()),
  ('00000000-0000-7000-0000-000000000003', '+919876500003', 'Kavya Reddy',   'female', 38, 'owner.lumiere@example.com',    'salon_owner', true, now(), now()),
  ('00000000-0000-7000-0000-000000000004', '+919876500004', 'Rahul Nair',    'male',   31, 'manager.lumiere@example.com',  'manager',     true, now(), now()),
  ('00000000-0000-7000-0000-000000000005', '+919876500005', 'Ravi Kumar',    'male',   27, 'ravi.stylist@example.com',     'stylist',     true, now(), now()),
  ('00000000-0000-7000-0000-000000000006', '+919876500006', 'Sneha Iyer',    'female', 41, 'owner.aura@example.com',       'salon_owner', true, now(), now())
ON CONFLICT (id) DO NOTHING;

-- ──────────────────────────────────────────────────────────────── salons ────────
-- location is 'lat,lng' text (see V005 migration + SalonService's javadoc — PostGIS is
-- not wired; proximity search is in-memory Haversine).
INSERT INTO salon_schema.salon (id, name, location, status, stylist_assignment_strategy, created_at, updated_at)
VALUES
  ('00000000-0000-7000-0001-000000000001', 'Lumière Salon & Spa',  '12.9784,77.6408', 'active', 'least_loaded', now(), now()),
  ('00000000-0000-7000-0001-000000000002', 'The Grooming Room',    '12.9352,77.6245', 'active', 'least_loaded', now(), now()),
  ('00000000-0000-7000-0001-000000000003', 'Aura Beauty Lounge',   '12.9121,77.6446', 'active', 'least_loaded', now(), now()),
  ('00000000-0000-7000-0001-000000000004', 'Studio Nine Hair Co.', '12.9719,77.6412', 'active', 'least_loaded', now(), now()),
  ('00000000-0000-7000-0001-000000000005', 'Serene Wellness Spa',  '12.9345,77.6265', 'active', 'least_loaded', now(), now()),
  ('00000000-0000-7000-0001-000000000006', 'Blush & Blow Bar',     '12.9089,77.6476', 'active', 'least_loaded', now(), now()),
  ('00000000-0000-7000-0001-000000000007', 'Copper & Comb',        '12.9250,77.5938', 'active', 'least_loaded', now(), now()),
  ('00000000-0000-7000-0001-000000000008', 'The Nail Atelier',     '12.9698,77.7500', 'active', 'least_loaded', now(), now())
ON CONFLICT (id) DO NOTHING;

-- Policies (15-min booking grid, 24h free cancellation).
INSERT INTO salon_schema.salon_policy (id, salon_id, template, free_cancel_hours, late_grace_minutes, require_prepayment, slot_granularity_minutes, created_at, updated_at)
SELECT
  ('00000000-0000-7000-0002-' || lpad(row_number() OVER (ORDER BY id)::text, 12, '0'))::uuid,
  id, 'standard', 24, 15, false, 15, now(), now()
FROM salon_schema.salon
ON CONFLICT (id) DO NOTHING;

-- Opening hours: 10:00–20:00 every day (0=Sun..6=Sat) for every salon.
INSERT INTO salon_schema.salon_hours (id, salon_id, day_of_week, open_time, close_time)
SELECT
  md5(s.id::text || d.day)::uuid, s.id, d.day, '10:00', '20:00'
FROM salon_schema.salon s
CROSS JOIN (SELECT generate_series(0, 6) AS day) d
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────── services ───────
-- Prices in PAISE (integer) — the locked money rule, never floats.
-- Session 43: `updated_at` removed. salon_service has NO such column (V002 defines only
-- created_at; V011 added `category`). The seed had been inserting it since Session 15 and would
-- abort here — and because this file is one transaction, EVERYTHING after it rolled back too.
-- Never caught because the users INSERT above failed first on most machines, hiding this one.
INSERT INTO salon_schema.salon_service (id, salon_id, name, price_paise, duration_minutes, requires_stylist_assignment, created_at)
VALUES
  ('00000000-0000-7000-0003-000000000001', '00000000-0000-7000-0001-000000000001', 'Haircut & styling', 90000, 45, true, now()),
  ('00000000-0000-7000-0003-000000000002', '00000000-0000-7000-0001-000000000001', 'Global hair colour', 350000, 120, true, now()),
  ('00000000-0000-7000-0003-000000000003', '00000000-0000-7000-0001-000000000001', 'Highlights / balayage', 480000, 180, true, now()),
  ('00000000-0000-7000-0003-000000000004', '00000000-0000-7000-0001-000000000001', 'Hair spa treatment', 150000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000005', '00000000-0000-7000-0001-000000000001', 'Deep-tissue massage (60 min)', 220000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000006', '00000000-0000-7000-0001-000000000001', 'Aromatherapy massage (90 min)', 300000, 90, true, now()),
  ('00000000-0000-7000-0003-000000000007', '00000000-0000-7000-0001-000000000001', 'Hydrating facial', 180000, 50, true, now()),
  ('00000000-0000-7000-0003-000000000008', '00000000-0000-7000-0001-000000000001', 'Anti-ageing facial', 280000, 70, true, now()),
  ('00000000-0000-7000-0003-000000000009', '00000000-0000-7000-0001-000000000001', 'Full-arm waxing', 60000, 30, true, now()),
  ('00000000-0000-7000-0003-000000000010', '00000000-0000-7000-0001-000000000001', 'Eyebrow threading', 15000, 15, true, now()),
  ('00000000-0000-7000-0003-000000000011', '00000000-0000-7000-0001-000000000002', 'Classic haircut', 55000, 40, true, now()),
  ('00000000-0000-7000-0003-000000000012', '00000000-0000-7000-0001-000000000002', 'Skin fade', 65000, 45, true, now()),
  ('00000000-0000-7000-0003-000000000013', '00000000-0000-7000-0001-000000000002', 'Hot-towel shave', 45000, 30, true, now()),
  ('00000000-0000-7000-0003-000000000014', '00000000-0000-7000-0001-000000000002', 'Beard trim & shape', 35000, 25, true, now()),
  ('00000000-0000-7000-0003-000000000015', '00000000-0000-7000-0001-000000000002', 'Beard colour', 70000, 40, true, now()),
  ('00000000-0000-7000-0003-000000000016', '00000000-0000-7000-0001-000000000002', 'Head massage (30 min)', 60000, 30, true, now()),
  ('00000000-0000-7000-0003-000000000017', '00000000-0000-7000-0001-000000000002', 'De-tan facial (men)', 120000, 45, true, now()),
  ('00000000-0000-7000-0003-000000000018', '00000000-0000-7000-0001-000000000003', 'Bridal makeup (trial)', 500000, 90, true, now()),
  ('00000000-0000-7000-0003-000000000019', '00000000-0000-7000-0001-000000000003', 'Bridal package (full day)', 2500000, 300, true, now()),
  ('00000000-0000-7000-0003-000000000020', '00000000-0000-7000-0001-000000000003', 'Engagement makeup', 800000, 120, true, now()),
  ('00000000-0000-7000-0003-000000000021', '00000000-0000-7000-0001-000000000003', 'Party makeup', 350000, 75, true, now()),
  ('00000000-0000-7000-0003-000000000022', '00000000-0000-7000-0001-000000000003', 'HD airbrush makeup', 600000, 90, true, now()),
  ('00000000-0000-7000-0003-000000000023', '00000000-0000-7000-0001-000000000003', 'Saree draping', 120000, 30, true, now()),
  ('00000000-0000-7000-0003-000000000024', '00000000-0000-7000-0001-000000000003', 'Gel manicure', 120000, 45, true, now()),
  ('00000000-0000-7000-0003-000000000025', '00000000-0000-7000-0001-000000000003', 'Classic pedicure', 100000, 50, true, now()),
  ('00000000-0000-7000-0003-000000000026', '00000000-0000-7000-0001-000000000003', 'Bridal mehendi', 450000, 180, true, now()),
  ('00000000-0000-7000-0003-000000000027', '00000000-0000-7000-0001-000000000003', 'Gold facial', 320000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000028', '00000000-0000-7000-0001-000000000004', 'Precision cut', 70000, 45, true, now()),
  ('00000000-0000-7000-0003-000000000029', '00000000-0000-7000-0001-000000000004', 'Keratin treatment', 450000, 150, true, now()),
  ('00000000-0000-7000-0003-000000000030', '00000000-0000-7000-0001-000000000004', 'Smoothening', 550000, 180, true, now()),
  ('00000000-0000-7000-0003-000000000031', '00000000-0000-7000-0001-000000000004', 'Root touch-up', 180000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000032', '00000000-0000-7000-0001-000000000004', 'Blow-dry & style', 65000, 35, true, now()),
  ('00000000-0000-7000-0003-000000000033', '00000000-0000-7000-0001-000000000004', 'Scalp treatment', 200000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000034', '00000000-0000-7000-0001-000000000005', 'Aromatherapy massage (90 min)', 300000, 90, true, now()),
  ('00000000-0000-7000-0003-000000000035', '00000000-0000-7000-0001-000000000005', 'Swedish massage (60 min)', 240000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000036', '00000000-0000-7000-0001-000000000005', 'Hot stone therapy', 380000, 90, true, now()),
  ('00000000-0000-7000-0003-000000000037', '00000000-0000-7000-0001-000000000005', 'Couples'' therapy session', 550000, 90, true, now()),
  ('00000000-0000-7000-0003-000000000038', '00000000-0000-7000-0001-000000000005', 'Body scrub & polish', 320000, 75, true, now()),
  ('00000000-0000-7000-0003-000000000039', '00000000-0000-7000-0001-000000000005', 'Steam & sauna (30 min)', 90000, 30, true, now()),
  ('00000000-0000-7000-0003-000000000040', '00000000-0000-7000-0001-000000000005', 'Detox facial', 260000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000041', '00000000-0000-7000-0001-000000000006', 'Blow-dry & style', 60000, 35, true, now()),
  ('00000000-0000-7000-0003-000000000042', '00000000-0000-7000-0001-000000000006', 'Curls / waves styling', 90000, 45, true, now()),
  ('00000000-0000-7000-0003-000000000043', '00000000-0000-7000-0001-000000000006', 'Nail art (per set)', 90000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000044', '00000000-0000-7000-0001-000000000006', 'Gel extensions', 150000, 75, true, now()),
  ('00000000-0000-7000-0003-000000000045', '00000000-0000-7000-0001-000000000006', 'Express manicure', 55000, 30, true, now()),
  ('00000000-0000-7000-0003-000000000046', '00000000-0000-7000-0001-000000000006', 'Party makeup', 280000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000047', '00000000-0000-7000-0001-000000000007', 'Barber cut', 65000, 40, true, now()),
  ('00000000-0000-7000-0003-000000000048', '00000000-0000-7000-0001-000000000007', 'Cut + beard combo', 95000, 60, true, now()),
  ('00000000-0000-7000-0003-000000000049', '00000000-0000-7000-0001-000000000007', 'Grey coverage', 140000, 50, true, now()),
  ('00000000-0000-7000-0003-000000000050', '00000000-0000-7000-0001-000000000007', 'Kids haircut', 40000, 30, true, now()),
  ('00000000-0000-7000-0003-000000000051', '00000000-0000-7000-0001-000000000008', 'Gel extensions', 45000, 75, true, now()),
  ('00000000-0000-7000-0003-000000000052', '00000000-0000-7000-0001-000000000008', 'Acrylic full set', 220000, 90, true, now()),
  ('00000000-0000-7000-0003-000000000053', '00000000-0000-7000-0001-000000000008', 'Nail repair (per nail)', 25000, 20, true, now()),
  ('00000000-0000-7000-0003-000000000054', '00000000-0000-7000-0001-000000000008', 'Luxury pedicure', 180000, 70, true, now()),
  ('00000000-0000-7000-0003-000000000055', '00000000-0000-7000-0001-000000000008', 'Hand spa treatment', 130000, 45, true, now())
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────── stylists ───────
-- overall_rating is NUMERIC(3,2) per V002.
INSERT INTO salon_schema.stylist (id, user_id, name, overall_rating, total_reviews, is_top_stylist, created_at)
VALUES
  ('00000000-0000-7000-0004-000000000001', '00000000-0000-7000-0000-000000000005', 'Ravi Kumar',      4.90, 156, true,  now()),
  ('00000000-0000-7000-0004-000000000002', NULL, 'Anita Rao',       4.80, 203, true,  now()),
  ('00000000-0000-7000-0004-000000000003', NULL, 'Farhan Ali',      4.70,  98, false, now()),
  ('00000000-0000-7000-0004-000000000004', NULL, 'Deepak Shetty',   4.80, 187, true,  now()),
  ('00000000-0000-7000-0004-000000000005', NULL, 'Meera Krishnan',  4.90, 264, true,  now()),
  ('00000000-0000-7000-0004-000000000006', NULL, 'Zoya Fernandes',  4.80, 141, true,  now()),
  ('00000000-0000-7000-0004-000000000007', NULL, 'Suresh Babu',     4.90, 312, true,  now())
ON CONFLICT (id) DO NOTHING;

-- Stylist ↔ salon links (portable identity: the link carries the per-salon rating).
INSERT INTO salon_schema.stylist_salon (id, stylist_id, salon_id, status, salon_rating, salon_review_count, is_available_today, joined_at)
VALUES
  ('00000000-0000-7000-0005-000000000001', '00000000-0000-7000-0004-000000000001', '00000000-0000-7000-0001-000000000001', 'active', 4.90, 156, true, now()),
  ('00000000-0000-7000-0005-000000000002', '00000000-0000-7000-0004-000000000002', '00000000-0000-7000-0001-000000000001', 'active', 4.80, 203, true, now()),
  ('00000000-0000-7000-0005-000000000003', '00000000-0000-7000-0004-000000000003', '00000000-0000-7000-0001-000000000001', 'active', 4.70,  98, true, now()),
  ('00000000-0000-7000-0005-000000000004', '00000000-0000-7000-0004-000000000004', '00000000-0000-7000-0001-000000000002', 'active', 4.80, 187, true, now()),
  ('00000000-0000-7000-0005-000000000005', '00000000-0000-7000-0004-000000000005', '00000000-0000-7000-0001-000000000003', 'active', 4.90, 264, true, now()),
  ('00000000-0000-7000-0005-000000000006', '00000000-0000-7000-0004-000000000006', '00000000-0000-7000-0001-000000000003', 'active', 4.80, 141, true, now()),
  ('00000000-0000-7000-0005-000000000007', '00000000-0000-7000-0004-000000000007', '00000000-0000-7000-0001-000000000005', 'active', 4.90, 312, true, now())
ON CONFLICT (id) DO NOTHING;

-- Stylist working hours: 10:00–20:00 every weekday, for every stylist at their salon.
INSERT INTO salon_schema.stylist_availability
  (id, stylist_id, salon_id, rule_type, day_of_week, specific_date, slot_type, start_time, end_time, blocks_booking, created_at, updated_at)
SELECT
  md5(ss.stylist_id::text || ss.salon_id::text || d.day)::uuid,
  ss.stylist_id, ss.salon_id, 'weekly_template', d.day, NULL, 'working', '10:00', '20:00', false, now(), now()
FROM salon_schema.stylist_salon ss
CROSS JOIN (SELECT generate_series(0, 6) AS day) d
ON CONFLICT (id) DO NOTHING;

-- ──────────────────────────────────────────────────────── staff seats ───────────
-- Owner + manager seats so those roles resolve a salonId on login.
-- Session 43: `status` and `updated_at` removed — salon_staff has neither. V002 defines exactly
-- (id, salon_id, user_id, role, created_at) and no later migration adds to it. Same class of
-- drift as salon_service above: the seed was written against a table shape that was imagined
-- rather than checked, and the all-or-nothing transaction meant it took the whole file down.
--
-- THIS is the row that makes owner login actually work. resolveSalonScope() looks up
-- salon_staff by user_id on every token mint; without it Kavya authenticates fine and then
-- gets 403 on every salon-scoped endpoint, because her JWT carries salonId = null.
INSERT INTO salon_schema.salon_staff (id, salon_id, user_id, role, created_at)
VALUES
  ('00000000-0000-7000-0006-000000000001', '00000000-0000-7000-0001-000000000001', '00000000-0000-7000-0000-000000000003', 'OWNER',   now()),
  ('00000000-0000-7000-0006-000000000002', '00000000-0000-7000-0001-000000000001', '00000000-0000-7000-0000-000000000004', 'MANAGER', now()),
  ('00000000-0000-7000-0006-000000000003', '00000000-0000-7000-0001-000000000003', '00000000-0000-7000-0000-000000000006', 'OWNER',   now())
ON CONFLICT (id) DO NOTHING;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════════
-- Verify:
--   SELECT count(*) FROM salon_schema.salon;          -- 8
--   SELECT count(*) FROM salon_schema.salon_service;  -- 55
--   SELECT count(*) FROM user_schema.users;           -- 6
-- ═══════════════════════════════════════════════════════════════════════════════
