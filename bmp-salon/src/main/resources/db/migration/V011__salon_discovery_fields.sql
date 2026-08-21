-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- V011 — the fields a customer needs to choose a salon.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- HOW THIS WAS FOUND (Session 40)
-- A sweep comparing every frontend Zod schema against the backend record it parses. Four
-- schemas asked for fields the server has never sent, and all four are on the customer's
-- discovery path:
--
--   SalonSchema        <- GET /salons       wants area, rating, reviewCount, categories,
--                                           startingPricePaise, topRated
--   SalonDetailSchema  <- GET /salons/{id}  wants all of the above PLUS about, address,
--                                           openHours, services[], stylists[]
--   ServiceSchema                           wants category
--   StylistSchema                           wants rating, reviewCount, speciality
--
-- `salon` has SEVEN columns: id, name, location, status, stylist_assignment_strategy, and the
-- two timestamps. Nothing a customer would use to pick a salon exists at all.
--
-- The parse throws, so **browse → salon detail → pick stylist → pick slot has never worked
-- against a real backend**. Every step before "Confirm" fails. It was invisible because
-- USE_MOCKS defaults ON and the mocks were written from the CLIENT's assumptions rather than
-- the server's contract — so the two never had to agree. Same root cause as the getSlots bug
-- in Session 38; that one was not an isolated case, and I should have swept then.
--
-- WHAT IS DELIBERATELY *NOT* ADDED HERE
--   · starting_price_paise — DERIVED from salon_service at read time. Storing it means a
--     second copy of the price that goes stale the moment an owner edits a service, and
--     "cheapest service" is a MIN over a handful of rows.
--   · open_hours as text — salon_hours already holds this properly, per weekday. A display
--     string is a presentation concern, composed in the response.
--   · image_hue / top_rated — pure client-side decoration. A hue is not a fact about a
--     business, and topRated is a threshold over `rating` that belongs wherever the threshold
--     is decided. Sending them would make the server responsible for the UI's colour scheme.
-- ═══════════════════════════════════════════════════════════════════════════════════════════

-- ---------------------------------------------------------------------------------------------
-- 1. Where the salon is, in words
-- ---------------------------------------------------------------------------------------------

-- The neighbourhood: "Indiranagar", "Koramangala". This is what a customer scans a list by —
-- `location` is a PostGIS point, which is right for distance and useless for reading.
ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS area VARCHAR(120);

-- The full street address.
--
-- SalonSignupSheet has COLLECTED this since Session 15 and thrown it away, because the
-- create-salon contract had nowhere to put it (PENDING_WORK F2). The field existed on the form,
-- the owner typed into it, and it went nowhere. This closes that.
ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS address TEXT;

-- Owner-written description. Nullable, and the UI must not invent one — Session 20 stripped
-- invented copy out of About/Contact for exactly this reason.
ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS about TEXT;

ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);

-- ---------------------------------------------------------------------------------------------
-- 1b. Where BOOKING ALERTS go
-- ---------------------------------------------------------------------------------------------
--
-- THE SALON HAS NEVER BEEN TOLD ABOUT A BOOKING.
-- `booking.created` reaches the customer and nobody else. The only way a salon learns someone
-- is coming is by having the desk open — TodayPanel polls every 60 seconds. A booking made
-- overnight, or while the tablet is shut, is invisible until somebody looks. For a salon, that
-- is the difference between a prepared morning and a surprise.
--
-- WHY THIS IS ON THE SALON AND NOT LOOKED UP FROM THE OWNER'S ACCOUNT
-- The owner's login is a person; the bookings inbox is a business function. A salon usually
-- wants alerts going to the shop's shared address and the front-desk handset, not to whoever
-- happened to create the account — and when that person leaves, the alerts should not leave with
-- them. Resolving salon_staff -> user_schema at send time would also mean a cross-service lookup
-- on the booking path to answer a question the salon can simply state once.
--
-- Both nullable. A salon that has set neither gets no alert, and the dispatcher says so in the
-- log rather than failing the booking — the appointment is real either way.
ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS booking_notify_email VARCHAR(160);
ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS booking_notify_phone VARCHAR(20);

-- ---------------------------------------------------------------------------------------------
-- 2. Reputation
-- ---------------------------------------------------------------------------------------------
--
-- Denormalised onto the salon, matching what stylist/stylist_salon already do. bmp-review owns
-- the source rows; this is the read model for a list that renders dozens of salons at once, and
-- a cross-service aggregate per row is not a thing a discovery screen can afford.
--
-- NULL means "no reviews yet" and is NOT the same as 0.00. A new salon showing "0.0 ★" reads as
-- terrible rather than new, and that is a real cost to the one salon least able to absorb it.
-- Every consumer must render null as "New" — see SalonDtos.
ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS rating NUMERIC(3,2);

ALTER TABLE salon_schema.salon ADD COLUMN IF NOT EXISTS review_count INT NOT NULL DEFAULT 0;

ALTER TABLE salon_schema.salon
    ADD CONSTRAINT chk_salon_rating CHECK (rating IS NULL OR (rating >= 0 AND rating <= 5));

-- ---------------------------------------------------------------------------------------------
-- 3. Categories
-- ---------------------------------------------------------------------------------------------
--
-- A CHILD TABLE, not a TEXT[] column and not a comma-separated string.
--
--   · Comma-separated is unqueryable and eventually contains a comma.
--   · TEXT[] would work in Postgres, but needs Hibernate's array JdbcType mapping — an exotic
--     type in a codebase that has none, for a table with at most five rows per salon.
--
-- A child table is boring, indexable, and joins the way every other relation here does.
-- "Salons offering hair colour near me" is a plain WHERE, which is the query this exists for.
CREATE TABLE IF NOT EXISTS salon_schema.salon_category (
    id UUID PRIMARY KEY NOT NULL,
    salon_id UUID NOT NULL,
    -- Free text rather than an enum: the taxonomy is not settled, and a CHECK constraint on a
    -- guess would need a migration every time the business learns something. Normalised to
    -- lower case by the service so "Hair" and "hair" are one category.
    category VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_salon_category ON salon_schema.salon_category(salon_id, category);
CREATE INDEX IF NOT EXISTS idx_salon_category_lookup ON salon_schema.salon_category(category);

ALTER TABLE salon_schema.salon_category
    ADD CONSTRAINT fk_salon_category_salon FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);

-- ---------------------------------------------------------------------------------------------
-- 4. Service category
-- ---------------------------------------------------------------------------------------------
-- Groups the menu on the salon page ("Hair", "Skin", "Nails"). Nullable — an ungrouped service
-- is fine and the UI puts it under "Other" rather than hiding it.
ALTER TABLE salon_schema.salon_service ADD COLUMN IF NOT EXISTS category VARCHAR(60);

-- ---------------------------------------------------------------------------------------------
-- 5. Stylist speciality
-- ---------------------------------------------------------------------------------------------
-- "Colour specialist", "Bridal". One short line under the name in the picker. The stylist's
-- rating and review count already exist (stylist.overall_rating, stylist_salon.salon_rating) —
-- only this was missing.
ALTER TABLE salon_schema.stylist ADD COLUMN IF NOT EXISTS speciality VARCHAR(120);

-- ---------------------------------------------------------------------------------------------
-- Browsing is filtered to approved salons and sorted by reputation. No backfill: every existing
-- salon reads as "New" with no area, which is exactly what they are — inventing an area or a
-- rating for them would be worse than a blank.
-- ---------------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_salon_discovery ON salon_schema.salon(status, rating DESC NULLS LAST);
