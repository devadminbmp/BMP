-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V026 — the salon's own customer book. Session 52.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE GAP ────────────────────────────────────────────────────────────────────────────────────
-- Somebody phones the salon, or walks in. Today the manager can only create a `walk_in_block`:
-- it claims the stylist's time and records nothing else. No customer, no booking, no invoice, no
-- history. The salon's busiest source of trade is invisible to the platform, and the customer is
-- a stranger every single visit.
--
-- `POST /bookings` cannot help, because it requires a `customerId` — a BMP user account — and a
-- person standing at the counter does not have one.
--
-- ── WHOSE CUSTOMER IS THIS? ────────────────────────────────────────────────────────────────────
-- Darshan's instruction, and it shapes the whole table: *"remember it's their own customer."*
--
-- So this is NOT a BMP user account. Somebody who gave their phone number to a receptionist has
-- not signed up to BMP, has not consented to anything from us, and must not be treated as ours.
-- Creating a silent `users` row for them would be a platform quietly acquiring people who never
-- agreed to it.
--
-- What it IS: a salon's private contact record, scoped to that salon, which lets the salon
-- recognise a repeat customer — "Priya, 4th visit" instead of a stranger every time.
--
-- ── linked_user_id: THE BRIDGE, WHEN THEY CHOOSE IT ────────────────────────────────────────────
-- Null until the same phone signs up to BMP themselves. Then the salon's record and the real
-- account can be associated, and their counter visits join their booking history.
--
-- Deliberately NOT auto-populated by matching phone numbers on a schedule. A phone number is not
-- proof of identity — numbers get recycled in India routinely — and silently attaching a
-- stranger's salon visits to somebody's account is the kind of privacy failure that is very hard
-- to undo. It is set only when there is a real reason to believe they are the same person.
--
-- ── UNIQUE PER SALON, NOT GLOBAL ───────────────────────────────────────────────────────────────
-- The same phone at two salons is two rows, on purpose. Each salon holds its own relationship
-- with that person; making it global would mean one salon's contact list leaking into another's,
-- and a single edit rewriting a record its owner never touched.

CREATE TABLE IF NOT EXISTS salon_schema.salon_customer (
    id UUID PRIMARY KEY NOT NULL,
    salon_id UUID NOT NULL,

    name VARCHAR(160) NOT NULL,
    -- E.164 without the +, matching the convention bmp-user uses. The receptionist types ten
    -- digits; normalisation happens in the service, so the stored form is always comparable.
    phone VARCHAR(20) NOT NULL,
    -- Optional. A counter conversation rarely produces an email and demanding one would slow
    -- down the fastest write in the product.
    email VARCHAR(200),

    -- Null until this person signs up to BMP themselves with this number. See the note above on
    -- why nothing populates it automatically.
    linked_user_id UUID,

    -- Denormalised on purpose. "Is this a regular?" is asked at the counter with somebody waiting,
    -- and it must not require counting bookings across a service boundary.
    visit_count INT NOT NULL DEFAULT 0,
    last_visit_at TIMESTAMPTZ,

    -- The salon's own note: "prefers Anjali", "allergic to ammonia". Theirs, never shown to BMP
    -- staff in the console and never used by the platform for anything.
    notes VARCHAR(1000),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE salon_schema.salon_customer
    ADD CONSTRAINT fk_salon_customer_salon FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);

-- One record per person per salon. This is what makes the second visit recognise the first —
-- without it, every booking creates a new row and the visit count never leaves 1.
CREATE UNIQUE INDEX IF NOT EXISTS uq_salon_customer_phone
    ON salon_schema.salon_customer (salon_id, phone);

-- A name is what the salon calls out when the appointment starts; blank makes the record useless.
ALTER TABLE salon_schema.salon_customer
    ADD CONSTRAINT chk_salon_customer_name CHECK (length(trim(name)) >= 1);

-- Ten to fifteen digits, no symbols. Deliberately permissive about country code but strict about
-- shape: a phone number with a space or a dash in it will not match on the customer's next visit,
-- and the unique index above is the whole point of this table.
ALTER TABLE salon_schema.salon_customer
    ADD CONSTRAINT chk_salon_customer_phone CHECK (phone ~ '^[0-9]{10,15}$');

ALTER TABLE salon_schema.salon_customer
    ADD CONSTRAINT chk_salon_customer_visits CHECK (visit_count >= 0);

-- The counter's lookup: type a few digits, find the regular. Salon-scoped, so one salon can never
-- search another's book.
CREATE INDEX IF NOT EXISTS idx_salon_customer_lookup
    ON salon_schema.salon_customer (salon_id, phone varchar_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_salon_customer_recent
    ON salon_schema.salon_customer (salon_id, last_visit_at DESC);

COMMENT ON TABLE salon_schema.salon_customer IS
    'A SALON''s own customer contact — not a BMP user account. Somebody who gave their number to '
    'a receptionist has not signed up to BMP and must not be treated as ours. linked_user_id '
    'associates them with a real account only if they sign up themselves. See V026.';
