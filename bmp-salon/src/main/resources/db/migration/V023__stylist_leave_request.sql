-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V023 — a stylist asks for leave; the salon approves it. Session 49.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── WHAT ALREADY WORKED, AND WHAT DIDN'T ───────────────────────────────────────────────────────
-- stylist_availability has carried rule_type='leave' since V003, and AvailabilityService already
-- subtracts those rows — so a stylist with a leave row for a date three weeks out ALREADY
-- disappears from that day's slots when the day arrives. The mechanism Darshan asked for exists.
--
-- What did not exist is how a leave row gets created. The only writer is the owner/manager
-- time-off endpoint, so a stylist could not ask for leave at all, and there was no record of
-- who asked, when, or whether anyone agreed.
--
-- This table is that record. It is deliberately SEPARATE from stylist_availability:
--
--   • stylist_availability answers "is this person bookable at 3pm on the 14th?" It is read on
--     every slot query and wants to stay a dumb, fast interval table.
--   • stylist_leave_request answers "who asked for what, and what did the salon say?" It has a
--     lifecycle, a decider, and a reason.
--
-- Collapsing them would put approval state on the hot availability path and mean a pending
-- request either blocks bookings before anyone agrees to it, or is indistinguishable from an
-- approved one. Approval WRITES availability rows; it does not become them.
--
-- ── WHY A PENDING REQUEST MUST NOT BLOCK ───────────────────────────────────────────────────────
-- The tempting shortcut is to block the slots as soon as leave is requested, "to be safe". That
-- silently takes a stylist off the calendar because they ASKED — so a request the owner would
-- have declined has already cost the salon a day of bookings, and nobody can see why.

CREATE TABLE IF NOT EXISTS salon_schema.stylist_leave_request (
    id UUID PRIMARY KEY NOT NULL,
    stylist_id UUID NOT NULL,
    salon_id UUID NOT NULL,

    -- Inclusive on both ends. A single day is start = end, which is the commonest case and
    -- should not require the stylist to understand a half-open interval.
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,

    -- NULL/NULL = the whole day, which is what "leave" usually means. Both set = a partial day
    -- ("I need Tuesday afternoon"), which maps to a partial-day stylist_availability row.
    start_time TIME,
    end_time TIME,

    -- annual / sick / unpaid / other. Free-ish, because a taxonomy imposed by a booking platform
    -- on somebody's personal circumstances will be wrong more often than useful.
    leave_type VARCHAR(20) NOT NULL DEFAULT 'other',
    reason VARCHAR(500),

    -- pending / approved / declined / cancelled
    status VARCHAR(12) NOT NULL DEFAULT 'pending',
    decided_by_user_id UUID,
    decided_at TIMESTAMPTZ,
    decision_note VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE salon_schema.stylist_leave_request
    ADD CONSTRAINT fk_leave_request_stylist FOREIGN KEY (stylist_id) REFERENCES salon_schema.stylist(id);
ALTER TABLE salon_schema.stylist_leave_request
    ADD CONSTRAINT fk_leave_request_salon FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id);

-- A backwards range is a typo, and one that would produce a leave that covers nothing while
-- looking approved. Cheaper to refuse than to explain later.
ALTER TABLE salon_schema.stylist_leave_request
    ADD CONSTRAINT chk_leave_range CHECK (ends_on >= starts_on);

-- Both times or neither. One alone is meaningless — "from 2pm" until when? — and the code that
-- turns this into an availability row would have to invent the other end.
ALTER TABLE salon_schema.stylist_leave_request
    ADD CONSTRAINT chk_leave_times
    CHECK ((start_time IS NULL AND end_time IS NULL)
           OR (start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time));

ALTER TABLE salon_schema.stylist_leave_request
    ADD CONSTRAINT chk_leave_status
    CHECK (status IN ('pending', 'approved', 'declined', 'cancelled'));

-- A decided request must say WHEN it was decided; a pending one must not pretend it was.
--
-- Written carefully, because the obvious version is vacuous:
--
--     CHECK ((status = 'pending' AND decided_at IS NULL) OR (status <> 'pending'))
--
-- The second branch is true for every non-pending row regardless of decided_at, so the whole
-- constraint permits an 'approved' row with no decision timestamp. Caught by a test that inserted
-- exactly that and expected a rejection. A CHECK that cannot fail is worse than no CHECK: it
-- reads like a guarantee.
--
-- 'cancelled' is deliberately in the "may have no decided_at" group. A request withdrawn while
-- still pending was never decided by anyone, and demanding a timestamp would mean inventing one.
ALTER TABLE salon_schema.stylist_leave_request
    ADD CONSTRAINT chk_leave_decided
    CHECK ((status = 'pending'  AND decided_at IS NULL)
        OR (status = 'cancelled')
        OR (status IN ('approved', 'declined') AND decided_at IS NOT NULL));

-- The salon's inbox: pending requests for this salon, oldest first. Partial, because the pending
-- set stays small while the decided set grows forever.
CREATE INDEX IF NOT EXISTS idx_leave_request_salon_pending
    ON salon_schema.stylist_leave_request (salon_id, starts_on)
    WHERE status = 'pending';

-- "What leave do I have?" — a stylist's own list, newest first.
CREATE INDEX IF NOT EXISTS idx_leave_request_stylist
    ON salon_schema.stylist_leave_request (stylist_id, starts_on DESC);

COMMENT ON TABLE salon_schema.stylist_leave_request IS
    'Leave asked for and answered. APPROVAL writes stylist_availability rows — this table is the '
    'paperwork, that table is what the booking algorithm reads. A pending request blocks nothing.';
