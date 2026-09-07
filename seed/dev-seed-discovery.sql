-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- Discovery data for the seeded salons. Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
--   psql -h localhost -U bmp -d bmp -f seed/dev-seed-discovery.sql
--
-- Run AFTER dev-seed.sql. Safe to run repeatedly (every statement is an UPDATE or an
-- ON CONFLICT DO NOTHING insert).
--
-- ── WHY THIS FILE EXISTS ───────────────────────────────────────────────────────────────────────
-- dev-seed.sql inserts salons with five columns: id, name, location, status, strategy. Everything
-- the discovery experience is built out of — area, address, about, image, rating, review_count,
-- categories, photos — was left NULL. Nobody noticed for a long time because the SALON LIST STILL
-- LOOKED FINE: names and distances rendered, and "0 verified reviews" and a plain gradient tile
-- are both legitimate states for a genuinely new salon.
--
-- The cost only showed up at the search box, and it showed up as the wrong diagnosis. Typing an
-- area returned "No salons match that search", which reads as a broken search feature. The search
-- was never broken — SalonService.near() matches on name, area, address AND live service names,
-- and has since Session 45. There was simply nothing in the columns it searches. Two hours can go
-- into debugging a query that is correct.
--
-- The general lesson, written down because it will happen again: EMPTY DATA IMPERSONATES BROKEN
-- CODE. A seed that produces a screen which renders without error is not a seed that exercises
-- the feature. Seed the columns the product actually reads, not the columns the table requires.
--
-- ── ON THE IMAGE URLS ──────────────────────────────────────────────────────────────────────────
-- Unsplash, with explicit width/quality parameters. Deliberately remote rather than committed to
-- the repo: these are dev fixtures for eyeballing layout, and binary files in git for that purpose
-- are a cost that never goes away. A real salon's photos go through the MinIO/S3 upload path
-- (SalonMediaController), which is a different mechanism entirely — do not take these URLs as an
-- example of how production images are stored.
--
-- If you are offline, the cards fall back to their gradient tile. That path is worth seeing too.


-- ── 1. Area, address, about, cover image ───────────────────────────────────────────────────────
-- Real Bengaluru localities, and the coordinates already in dev-seed.sql genuinely fall in them —
-- so "salons near me" and "salons in Indiranagar" agree with each other. Seed data that contradicts
-- itself teaches you to distrust the feature rather than the fixture.
UPDATE salon_schema.salon SET
    area      = 'Indiranagar',
    address   = '12th Main Road, HAL 2nd Stage, Indiranagar, Bengaluru 560038',
    about     = 'A calm, full-service salon and spa on 12th Main. Known for balayage, keratin '
                || 'treatments and a genuinely unhurried head massage.',
    image_url = 'https://images.unsplash.com/photo-1560066984-138dadb4c035?w=1200&q=80',
    rating       = 4.8,
    review_count = 214
WHERE id = '00000000-0000-7000-0001-000000000001';

UPDATE salon_schema.salon SET
    area      = 'Koramangala',
    address   = '5th Block, 80 Feet Road, Koramangala, Bengaluru 560095',
    about     = 'A men''s grooming room built around the classics — scissor cuts, beard sculpting '
                || 'and hot-towel shaves. Walk-ins welcome before noon.',
    image_url = 'https://images.unsplash.com/photo-1585747860715-2ba37e788b70?w=1200&q=80',
    rating       = 4.6,
    review_count = 178
WHERE id = '00000000-0000-7000-0001-000000000002';

UPDATE salon_schema.salon SET
    area      = 'HSR Layout',
    address   = 'Sector 2, 27th Main Road, HSR Layout, Bengaluru 560102',
    about     = 'Skin and beauty studio focused on facials, peels and bridal packages. Consultation '
                || 'is free and never rushed.',
    image_url = 'https://images.unsplash.com/photo-1522337360788-8b13dee7a37e?w=1200&q=80',
    rating       = 4.7,
    review_count = 96
WHERE id = '00000000-0000-7000-0001-000000000003';

UPDATE salon_schema.salon SET
    area      = 'Indiranagar',
    address   = '100 Feet Road, Defence Colony, Indiranagar, Bengaluru 560038',
    about     = 'A hair-only studio. Colour correction, curly cuts and extensions, by stylists who '
                || 'do nothing else.',
    image_url = 'https://images.unsplash.com/photo-1521590832167-7bcbfaa6381f?w=1200&q=80',
    rating       = 4.9,
    review_count = 331
WHERE id = '00000000-0000-7000-0001-000000000004';

UPDATE salon_schema.salon SET
    area      = 'Koramangala',
    address   = '6th Block, 17th Main Road, Koramangala, Bengaluru 560095',
    about     = 'Ayurvedic and deep-tissue massage, steam and body therapies. Quiet rooms, no '
                || 'upselling.',
    image_url = 'https://images.unsplash.com/photo-1540555700478-4be289fbecef?w=1200&q=80',
    rating       = 4.5,
    review_count = 142
WHERE id = '00000000-0000-7000-0001-000000000005';

UPDATE salon_schema.salon SET
    area      = 'HSR Layout',
    address   = 'Sector 7, 14th Main Road, HSR Layout, Bengaluru 560102',
    about     = 'Blow-dry bar and makeup studio. In and out in forty minutes, party-ready.',
    image_url = 'https://images.unsplash.com/photo-1595476108010-b4d1f102b1b1?w=1200&q=80',
    rating       = 4.4,
    review_count = 87
WHERE id = '00000000-0000-7000-0001-000000000006';

-- No rating and no reviews ON PURPOSE. This is the one salon that exercises the genuinely-new
-- state: the card must render "New" rather than a zero, and it must not be filtered out of the
-- list for lacking a score. Every salon having a rating is exactly how that bug ships unnoticed.
UPDATE salon_schema.salon SET
    area      = 'Jayanagar',
    address   = '4th Block, 11th Main Road, Jayanagar, Bengaluru 560011',
    about     = 'A small neighbourhood barbershop. Two chairs, no appointments needed after 4pm.',
    image_url = 'https://images.unsplash.com/photo-1503951914875-452162b0f3f1?w=1200&q=80',
    rating       = NULL,
    review_count = 0
WHERE id = '00000000-0000-7000-0001-000000000007';

UPDATE salon_schema.salon SET
    area      = 'Whitefield',
    address   = 'ITPL Main Road, Whitefield, Bengaluru 560066',
    about     = 'Nail art, gel extensions and pedicures. Bring a reference photo — they will match it.',
    image_url = 'https://images.unsplash.com/photo-1604654894610-df63bc536371?w=1200&q=80',
    rating       = 4.7,
    review_count = 121
WHERE id = '00000000-0000-7000-0001-000000000008';


-- ── 2. Categories ──────────────────────────────────────────────────────────────────────────────
-- The category rail on Discover filters through salon_category, and the table was empty — so every
-- chip except "All" returned nothing. A filter that always returns nothing reads as a broken
-- product, not as an honest empty result.
--
-- LOWER CASE on purpose: SalonService normalises the category before matching, so 'Hair' seeded
-- here would never match a query for 'hair'. Storing what the service expects rather than what
-- looks tidy in a table viewer.
INSERT INTO salon_schema.salon_category (id, salon_id, category, created_at)
VALUES
  ('00000000-0000-7000-0009-000000000001', '00000000-0000-7000-0001-000000000001', 'hair',     now()),
  ('00000000-0000-7000-0009-000000000002', '00000000-0000-7000-0001-000000000001', 'spa',      now()),
  ('00000000-0000-7000-0009-000000000003', '00000000-0000-7000-0001-000000000001', 'skin',     now()),
  ('00000000-0000-7000-0009-000000000004', '00000000-0000-7000-0001-000000000002', 'grooming', now()),
  ('00000000-0000-7000-0009-000000000005', '00000000-0000-7000-0001-000000000002', 'hair',     now()),
  ('00000000-0000-7000-0009-000000000006', '00000000-0000-7000-0001-000000000003', 'skin',     now()),
  ('00000000-0000-7000-0009-000000000007', '00000000-0000-7000-0001-000000000003', 'bridal',   now()),
  ('00000000-0000-7000-0009-000000000008', '00000000-0000-7000-0001-000000000003', 'waxing',   now()),
  ('00000000-0000-7000-0009-000000000009', '00000000-0000-7000-0001-000000000004', 'hair',     now()),
  ('00000000-0000-7000-0009-00000000000a', '00000000-0000-7000-0001-000000000005', 'massage',  now()),
  ('00000000-0000-7000-0009-00000000000b', '00000000-0000-7000-0001-000000000005', 'spa',      now()),
  ('00000000-0000-7000-0009-00000000000c', '00000000-0000-7000-0001-000000000006', 'makeup',   now()),
  ('00000000-0000-7000-0009-00000000000d', '00000000-0000-7000-0001-000000000006', 'hair',     now()),
  ('00000000-0000-7000-0009-00000000000e', '00000000-0000-7000-0001-000000000006', 'bridal',   now()),
  ('00000000-0000-7000-0009-00000000000f', '00000000-0000-7000-0001-000000000007', 'grooming', now()),
  ('00000000-0000-7000-0009-000000000010', '00000000-0000-7000-0001-000000000007', 'hair',     now()),
  ('00000000-0000-7000-0009-000000000011', '00000000-0000-7000-0001-000000000008', 'nails',    now())
ON CONFLICT (id) DO NOTHING;


-- ── 3. Gallery photos ──────────────────────────────────────────────────────────────────────────
-- Three salons get a gallery; the rest get none. Deliberately uneven — the salon page has to look
-- right both with and without one, and a fixture where every salon has photos never shows you the
-- empty case.
INSERT INTO salon_schema.salon_photo (id, salon_id, url, caption, sort_order, created_at)
VALUES
  ('00000000-0000-7000-000a-000000000001', '00000000-0000-7000-0001-000000000001',
   'https://images.unsplash.com/photo-1560066984-138dadb4c035?w=1200&q=80', 'The colour bar', 0, now()),
  ('00000000-0000-7000-000a-000000000002', '00000000-0000-7000-0001-000000000001',
   'https://images.unsplash.com/photo-1522337360788-8b13dee7a37e?w=1200&q=80', 'Treatment room', 1, now()),
  ('00000000-0000-7000-000a-000000000003', '00000000-0000-7000-0001-000000000001',
   'https://images.unsplash.com/photo-1487412947147-5cebf100ffc2?w=1200&q=80', 'Reception', 2, now()),
  ('00000000-0000-7000-000a-000000000004', '00000000-0000-7000-0001-000000000004',
   'https://images.unsplash.com/photo-1521590832167-7bcbfaa6381f?w=1200&q=80', 'Styling floor', 0, now()),
  ('00000000-0000-7000-000a-000000000005', '00000000-0000-7000-0001-000000000004',
   'https://images.unsplash.com/photo-1562322140-8baeececf3df?w=1200&q=80', 'Wash stations', 1, now()),
  ('00000000-0000-7000-000a-000000000006', '00000000-0000-7000-0001-000000000005',
   'https://images.unsplash.com/photo-1540555700478-4be289fbecef?w=1200&q=80', 'Massage suite', 0, now()),
  ('00000000-0000-7000-000a-000000000007', '00000000-0000-7000-0001-000000000005',
   'https://images.unsplash.com/photo-1600334089648-b0d9d3028eb2?w=1200&q=80', 'Steam room', 1, now())
ON CONFLICT (id) DO NOTHING;


-- ── 4. What this makes searchable ──────────────────────────────────────────────────────────────
-- After running this, all of these return results on Discover:
--
--   "indiranagar" / "koramangala" / "hsr" / "jayanagar" / "whitefield"   -> area + address
--   "copper" / "blush" / "atelier"                                       -> salon name
--   "balayage" / "pedicure" / "shave"                                    -> live service names
--   the category chips: hair, spa, skin, nails, bridal, grooming, makeup, massage, waxing
--
-- Note the matching is a plain case-insensitive substring — "kormangala" (a transposed 'a') finds
-- nothing, and that is the current, honest behaviour. Fuzzy matching is a real feature with real
-- trade-offs, not something to smuggle in through a seed file.
