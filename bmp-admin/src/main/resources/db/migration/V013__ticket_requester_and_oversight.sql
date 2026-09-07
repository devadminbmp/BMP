-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  V013 — WHO is asking, and who is working it. Session 64.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  THE PROBLEM THIS FIXES
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  `raised_by_type` has been on this table since V002. It is set correctly on every ticket, it is
--  carried in the service-layer DTO, and it is rendered NOWHERE. `grep -rn raisedByType BMP-ADMIN/src`
--  returned zero hits.
--
--  So an agent opening the queue saw a customer complaint and a salon owner reporting that their
--  whole listing is down as the same grey row. They could not prioritise by requester because the
--  requester was invisible — which in turn made the `priority` column, which HAS existed and HAS
--  been rendered since V002, effectively unusable: nobody could judge what deserved 'urgent'.
--
--  A column that is written and never read is not a feature. It is a promise the UI never kept.
--
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  WHY A NAME SNAPSHOT RATHER THAN A LOOKUP
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  `requester_name` is denormalised onto the ticket at raise time, exactly as bmp-booking snapshots
--  customer contact onto the booking row (V006 there, and for the same reasons):
--
--    · The queue lists 50 tickets. Resolving names live means 50 cross-service calls per page load,
--      each of which can fail, and the failure blanks a name in a list — an agent then cannot tell
--      "no name recorded" from "bmp-user is down".
--    · A ticket is a record of a conversation that happened. If the person later changes their
--      display name, or deletes their account under DPDP, the ticket should still say who it was
--      about at the time. A live join would rewrite history, and for a deleted account would erase
--      the requester from an audit record we are obliged to keep.
--
--  Nullable, because tickets created before this migration have no snapshot and inventing one would
--  be worse than an honest blank.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

-- Who asked, captured at the moment they asked. See above for why this is a copy, not a join.
ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS requester_name VARCHAR(160);

-- The salon's name, same reasoning. An agent triaging needs "The Grooming Room", not a UUID they
-- have to paste into another screen to resolve.
ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS salon_name VARCHAR(160);

/*
 * Was the priority chosen by a person, or derived by the system?
 *
 * This matters more than it looks. TicketPriorityPolicy raises a salon-owner payment issue to
 * 'high' automatically — but if an agent then judges it routine and drops it to 'medium', a later
 * re-derivation must NOT quietly undo their decision. Without this flag the only way to avoid
 * fighting the agent is to never re-derive, which means the policy can never be applied to an
 * existing ticket at all.
 *
 * false = a human set this and the system keeps its hands off.
 */
ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS priority_auto BOOLEAN NOT NULL DEFAULT true;

/*
 * Assignment history — who has held this ticket, and why it moved.
 *
 * `ticket_escalation` (V009) records moves UP the tier ladder. This records every move sideways or
 * down as well: reassignment when someone goes on leave, a transfer to finance because the question
 * is about a payout rather than a booking, an agent picking up an unassigned ticket.
 *
 * Kept separate from ticket_escalation rather than widening it, because they answer different
 * questions and are read by different screens: escalation is "was this handled at the right level",
 * assignment is "who is working what right now". Merging them would mean every workload query
 * filters out escalation rows and every escalation audit filters out reassignments.
 *
 * Append-only. An assignment trail that can be edited is not a trail.
 */
CREATE TABLE IF NOT EXISTS admin_schema.ticket_assignment (
    id             UUID PRIMARY KEY NOT NULL,           -- UUIDv7
    ticket_id      UUID NOT NULL,

    -- NULL means the ticket was unassigned (returned to the pool). A real state, not missing data.
    from_staff_id  UUID,
    to_staff_id    UUID,

    -- Who performed the move. Not the same as from_staff_id: an ops admin reassigning somebody
    -- else's ticket is the common case, and conflating the two loses exactly the fact worth having.
    actor_staff_id UUID NOT NULL,

    -- 'claim' | 'assign' | 'reassign' | 'transfer' | 'release' | 'escalate'
    -- A closed vocabulary so the workload screens can group by it. Free text here would mean every
    -- report has to guess which spellings mean the same thing.
    action         VARCHAR(20) NOT NULL,

    -- Required for a transfer, optional otherwise. "Why is this on my queue" is the question an
    -- agent asks first, and a transfer with no reason makes them ask a person instead.
    reason         TEXT,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_ticket_assignment_ticket
        FOREIGN KEY (ticket_id) REFERENCES admin_schema.support_ticket(id)
);

-- Append-only at the database level, matching audit_log (V002). The application never updates or
-- deletes these rows, and this makes that guarantee rather than merely documenting it.
REVOKE UPDATE, DELETE ON admin_schema.ticket_assignment FROM PUBLIC;

CREATE INDEX IF NOT EXISTS idx_ticket_assignment_ticket
    ON admin_schema.ticket_assignment (ticket_id, created_at DESC);

-- "What is this agent holding?" — the oversight screen's main query.
CREATE INDEX IF NOT EXISTS idx_ticket_assignment_staff
    ON admin_schema.ticket_assignment (to_staff_id, created_at DESC);

/*
 * The oversight screen's hot path: every open ticket, newest first, with its owner.
 *
 * Partial on the open states deliberately. Resolved and closed tickets are the large majority
 * after a few months and are never in this view, so indexing them would grow the index without
 * ever serving a query from it.
 */
CREATE INDEX IF NOT EXISTS idx_support_ticket_oversight
    ON admin_schema.support_ticket (assigned_staff_id, priority, created_at)
    WHERE status IN ('open', 'in_progress', 'waiting_on_user');

-- Triaging by requester is the whole point of V013; this makes it cheap.
CREATE INDEX IF NOT EXISTS idx_support_ticket_requester_kind
    ON admin_schema.support_ticket (raised_by_type, status);

COMMENT ON COLUMN admin_schema.support_ticket.requester_name IS
    'Snapshot of who raised this, taken at raise time. Deliberately not a join — see V013 header.';
COMMENT ON COLUMN admin_schema.support_ticket.priority_auto IS
    'true = derived by TicketPriorityPolicy and safe to re-derive. false = a human decided; leave it alone.';
COMMENT ON TABLE admin_schema.ticket_assignment IS
    'Append-only trail of who held a ticket and why it moved. Tier escalations also appear in ticket_escalation.';
