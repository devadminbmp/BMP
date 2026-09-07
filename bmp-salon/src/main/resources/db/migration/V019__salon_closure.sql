-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V019 — salon closures: holidays, half-days, "shut for two hours". Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- A salon closes for Diwali, for a family event, for a power cut, or for an hour while the water
-- tanker comes. Until now BMP had no way to express that. The only workaround was to delete or
-- edit opening hours, which is destructive, wrong (it changes the RULE rather than the exception)
-- and impossible to undo cleanly.
--
-- The consequence was worse than the missing feature: customers kept booking into a shut salon,
-- and the salon found out when somebody arrived at a locked shutter.
--
-- ── AN ARBITRARY WINDOW, NOT A DATE ───────────────────────────────────────────────────────────
-- Stored as start/end instants, not a date or a day flag. "Closed on the 14th" and "closed 2pm–4pm
-- on the 14th" are the same shape, so there is one code path, one availability check and one set
-- of edge cases. A separate is_full_day column would be a second representation of a fact the
-- window already contains.
--
-- Instants (TIMESTAMPTZ), not local times: everything else in this system is UTC-on-the-wire and
-- rendered in Asia/Kolkata by BmpTimeZone. A closure that used naive local time would be the one
-- place where a comparison against a booking's start_at needed a conversion, and that is exactly
-- where an off-by-five-and-a-half-hours bug lives.
--
-- ── WHY NO CASCADE TO BOOKINGS ────────────────────────────────────────────────────────────────
-- Deliberately no foreign key and no automatic cancellation. Bookings live in bmp-booking, a
-- different service and a different schema; and more importantly, what happens to an affected
-- booking is a decision (reschedule or refund), not a consequence. The closure records the fact;
-- SalonClosureService surfaces the affected bookings and the owner resolves each one.
--
-- BOOKKEEPING FAILING MUST NEVER CANCEL THE THING BEING BOOKED — and the reverse holds too: a
-- closure being recorded must never silently destroy somebody's appointment.

CREATE TABLE IF NOT EXISTS salon_schema.salon_closure (
    id          UUID PRIMARY KEY NOT NULL,            -- UUIDv7
    salon_id    UUID NOT NULL,

    -- The shut window. Half-open [starts_at, ends_at): a booking exactly at ends_at is FINE, the
    -- salon has reopened. The same convention the availability algorithm already uses for slots,
    -- so the two agree without anybody having to remember which end is inclusive.
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ NOT NULL,

    -- Shown to customers who had a booking in the window: "Closed for Diwali". Optional, because
    -- forcing a reason produces "closed" — and a salon is entitled to shut without explaining.
    reason      VARCHAR(200),

    -- Who recorded it. Owner or manager; useful when two people manage one salon and the closure
    -- surprises one of them.
    created_by  UUID,

    -- Soft-cancelled rather than deleted: an owner who closes for a day and then reopens has
    -- customers who were already told. Keeping the row means we can tell them it is back on, and
    -- means the audit of "why did that booking get moved" survives.
    cancelled_at TIMESTAMPTZ,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A closure that ends before it starts is meaningless and would silently match no bookings —
    -- the worst kind of wrong, because the owner would believe they had closed.
    CONSTRAINT chk_salon_closure_window CHECK (ends_at > starts_at)
);

-- The availability algorithm asks "is this salon closed at time T" for every candidate slot, so
-- this index is on the hot path of every search that reaches a salon page.
CREATE INDEX IF NOT EXISTS idx_salon_closure_lookup
    ON salon_schema.salon_closure (salon_id, starts_at, ends_at)
    WHERE cancelled_at IS NULL;

COMMENT ON TABLE salon_schema.salon_closure IS
    'Exceptions to a salon''s normal opening hours — holidays, half-days, short closures. '
    'Half-open windows [starts_at, ends_at). Does not touch bookings; see SalonClosureService.';
