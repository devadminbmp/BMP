-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V020 — a stylist asks to join a salon, and the owner decides. Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE CHANGE IN SHAPE ────────────────────────────────────────────────────────────────────────
-- Until now the only way to become a stylist on BMP was to be INVITED: the owner created a token,
-- the stylist redeemed it, and the stylist_salon link was created as a side effect of that.
-- Identity was a consequence of employment.
--
-- That works, and it has one real cost: a stylist who changes salon starts over. Their ratings,
-- their review count and their speciality all live on a row that was created by their old employer
-- for their old employer. The person doing the work does not own the record of having done it.
--
-- So a stylist can now sign up for themselves, and ASK to join a salon. This table is that ask.
--
-- ── WHY A REQUEST AND NOT A DIRECT LINK ────────────────────────────────────────────────────────
-- "I work at Lumière" typed into a form is a claim, not a fact. If self-signup created the link
-- directly, anyone could attach themselves to any salon — appearing on its public page, in its
-- stylist picker, and taking its bookings. The owner has to agree, and this table is where the
-- agreement is recorded.
--
-- The invite flow is NOT replaced. An owner who wants to add somebody directly still can; this is
-- the other direction, for the stylist who found BMP on their own.
--
-- ── ONE PENDING REQUEST PER PAIR ───────────────────────────────────────────────────────────────
-- Enforced by a partial unique index rather than checked in Java. A stylist who taps the button
-- twice, or whose request is still sitting unanswered, must not fill the owner's inbox with
-- duplicates of the same ask — and a check-then-insert in the service races with itself exactly
-- the way the old ticket-reference counter did.
--
-- Partial, on status='pending', because a DECLINED request must not block a later one. People
-- change jobs, and salons change their minds.

CREATE TABLE IF NOT EXISTS salon_schema.stylist_join_request (
    id          UUID PRIMARY KEY NOT NULL,          -- UUIDv7
    stylist_id  UUID NOT NULL,
    salon_id    UUID NOT NULL,

    -- pending | accepted | declined | withdrawn
    --   withdrawn = the stylist changed their mind before a decision. Kept distinct from declined
    --   so an owner is never shown as having refused somebody who simply left.
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',

    -- The stylist's note: "I've worked here since March, Priya knows me." Optional, and worth
    -- having — an owner deciding on a name alone will decline anyone they don't instantly place.
    message     VARCHAR(500),

    -- The owner's reason, on a decline. Shown to the stylist, so "no" is not a dead end.
    decision_note VARCHAR(500),

    decided_at  TIMESTAMPTZ,
    -- The USER id of the owner or manager who decided. Not a staff row id: staff rows can be
    -- removed when somebody leaves, and the record of who made the decision must outlive them.
    decided_by  UUID,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_join_request_status
        CHECK (status IN ('pending', 'accepted', 'declined', 'withdrawn')),

    -- A decision must carry its timestamp, and a pending request must not have one. Cheap, and it
    -- catches the half-written update that leaves a request looking answered but with no date.
    CONSTRAINT chk_join_request_decided
        CHECK ((status = 'pending' AND decided_at IS NULL)
            OR (status <> 'pending' AND decided_at IS NOT NULL)
            OR (status = 'withdrawn'))
);

-- One live ask per stylist per salon. See the header for why this is an index and not a service check.
CREATE UNIQUE INDEX IF NOT EXISTS uq_join_request_pending
    ON salon_schema.stylist_join_request (stylist_id, salon_id)
    WHERE status = 'pending';

-- The owner's inbox: "what is waiting for me", oldest first.
CREATE INDEX IF NOT EXISTS idx_join_request_salon
    ON salon_schema.stylist_join_request (salon_id, status, created_at);

-- The stylist's own view of where they have applied.
CREATE INDEX IF NOT EXISTS idx_join_request_stylist
    ON salon_schema.stylist_join_request (stylist_id, created_at DESC);

COMMENT ON TABLE salon_schema.stylist_join_request IS
    'A self-registered stylist asking an owner to add them to a salon. The owner''s acceptance is '
    'what creates the stylist_salon link — a stylist can never link themselves. See V020.';
