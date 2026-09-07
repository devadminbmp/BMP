-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- V028 — A SALON CAN INVITE A STYLIST WHO ALREADY HAS AN ACCOUNT. Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- Darshan:
--   "while inviting we need take stylist email id also hence we can send him invitation and code
--    beautifully in email but u r taking only name and number"
--   "i have login invite code but after login i cant able see any invite notification"
--   "invitation code as optional — stylist can create account without invitation code also. once
--    he have account salon also search via number or email and ask him to join. and stylist will
--    get notification and accept"
--   "stylist can leave salon or even salon can revoke, so if have account next other salon send
--    notification to join and he join easily with account"
--
-- ── WHAT EXISTED, AND THE HOLE BETWEEN THE TWO HALVES ──────────────────────────────────────────
-- There were already two ways to put a stylist on a salon's team, and neither covered the case
-- Darshan is describing:
--
--   1. staff_invites (V002/V008) — the salon invites a PHONE NUMBER with a one-time code. Built for
--      somebody who has never used BMP. The code is redeemed during signup and the link is made.
--      Nothing to accept in-app, because there is no account yet to show it to.
--
--   2. stylist_join_request (V020) — the STYLIST finds the salon and asks. The owner decides.
--
-- So: salon → stranger works. Stylist → salon works. Salon → EXISTING STYLIST does not exist at
-- all. That is exactly the state in Darshan's screenshots: a stylist signed in, holding an invite
-- code, with nothing to accept anywhere, because the code path assumes no account and the request
-- path only runs the other way.
--
-- ── WHY THIS EXTENDS join_request RATHER THAN ADDING AN invitations TABLE ──────────────────────
-- A salon inviting a stylist and a stylist asking a salon are THE SAME AGREEMENT, proposed from
-- opposite ends. Both are pending until somebody answers; both end accepted, declined or
-- withdrawn; both must respect one-active-salon (V021); both create the same stylist_salon row.
--
-- A second table would duplicate every one of those rules and then drift from them — which is the
-- failure this codebase has hit repeatedly (see Session 65's authority-matrix and unlock findings:
-- two copies of one decision, and the looser copy is the one that runs).
--
-- One column says which way it was proposed. Everything else is shared.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

-- ── 1. WHICH WAY WAS THIS PROPOSED? ────────────────────────────────────────────────────────────
--
-- Defaulting to 'stylist_to_salon' is what makes this migration safe on existing data: every row
-- written before today was, by construction, a stylist asking a salon. The default states that
-- fact rather than leaving a NULL for later code to guess at.
ALTER TABLE salon_schema.stylist_join_request
    ADD COLUMN IF NOT EXISTS direction VARCHAR(20) NOT NULL DEFAULT 'stylist_to_salon';

ALTER TABLE salon_schema.stylist_join_request
    DROP CONSTRAINT IF EXISTS chk_join_request_direction;
ALTER TABLE salon_schema.stylist_join_request
    ADD CONSTRAINT chk_join_request_direction
    CHECK (direction IN ('stylist_to_salon', 'salon_to_stylist'));

COMMENT ON COLUMN salon_schema.stylist_join_request.direction IS
    'stylist_to_salon = the stylist asked, the salon decides (V020). '
    'salon_to_stylist = the salon invited, the STYLIST decides (V028). Same agreement, opposite '
    'ends. The direction decides WHO MAY ACCEPT, which is the whole reason it is stored.';

-- Who sent an invitation. Null for a stylist-initiated request — nobody invited them.
ALTER TABLE salon_schema.stylist_join_request
    ADD COLUMN IF NOT EXISTS invited_by_user_id UUID;

COMMENT ON COLUMN salon_schema.stylist_join_request.invited_by_user_id IS
    'The owner or manager who sent the invitation. Null when the stylist asked first.';


-- ── 2. ONE OPEN CONVERSATION PER PAIR, WHICHEVER WAY IT WAS STARTED ────────────────────────────
--
-- Without this, a salon can invite a stylist who has already asked them, and now there are two
-- pending rows for one agreement. Accepting either leaves the other dangling forever, and the
-- stylist sees an invitation to a salon they are already on the team of.
--
-- Partial on 'pending' deliberately: a pair may have any number of RESOLVED rows. Somebody who
-- declined in March and is invited again in October is a normal thing to happen, and a plain
-- unique index would forbid it.
--
-- Note the index does NOT include direction. That is the point: it does not matter who asked, only
-- that one open question exists between these two parties at a time.
CREATE UNIQUE INDEX IF NOT EXISTS uq_join_request_one_open_per_pair
    ON salon_schema.stylist_join_request (stylist_id, salon_id)
    WHERE status = 'pending';

-- The stylist's inbox: "what am I being asked to join?" Ordered newest first.
CREATE INDEX IF NOT EXISTS idx_join_request_stylist_pending
    ON salon_schema.stylist_join_request (stylist_id, status, created_at DESC);


-- ── 3. AN EMAIL ON THE CODE INVITE, SO THE CODE CAN ACTUALLY BE SENT ──────────────────────────
--
-- staff_invites has held a phone and (since V008) a name. Darshan's point is that a code nobody
-- can deliver is a code the owner has to read out over the phone — which is how a 32-character
-- token gets mistyped and blamed on the app.
--
-- NULLABLE, and that is deliberate rather than lazy: an owner inviting the person standing in
-- front of them has no reason to know their email, and demanding one would block the commonest
-- case to serve the convenient one. With an email we send it; without, the owner shares the code
-- themselves, exactly as today.
ALTER TABLE salon_schema.staff_invites
    ADD COLUMN IF NOT EXISTS invitee_email VARCHAR(160);

COMMENT ON COLUMN salon_schema.staff_invites.invitee_email IS
    'Where to email the invite code. Nullable — an owner inviting somebody standing in front of '
    'them may not know it, and requiring it would block the commonest case. Session 65.';

-- Did we manage to send it? Distinct from "was an email supplied", so an owner can tell the
-- difference between "I never gave an address" and "we tried and it did not go".
ALTER TABLE salon_schema.staff_invites
    ADD COLUMN IF NOT EXISTS emailed_at TIMESTAMPTZ;

COMMENT ON COLUMN salon_schema.staff_invites.emailed_at IS
    'When the code was emailed. Null with an email present means the send failed or has not run '
    'yet — a state the owner needs to see, because they are the fallback delivery channel.';
