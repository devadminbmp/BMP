-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  V014 — which DESK holds this, and what the person waiting is allowed to know. Session 64.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  TWO DIFFERENT MOVES, AND WHY THE EXISTING ONE ISN'T ENOUGH
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  V009 gave tickets a `tier` and an escalation ladder: agent → lead → ops → owner. That models
--  "this is harder than I can handle" and it works.
--
--  It cannot model "this isn't my job". A refund dispute sitting with a support agent does not need
--  a MORE SENIOR support person — it needs FINANCE, who are deliberately tier 0 and off the ladder
--  entirely (TicketEscalationService: "Analysts and finance are not on the ladder"). Escalating it
--  three times to reach the platform owner and having them forward it by hand is not a workflow.
--
--  So: `handling_desk` is orthogonal to `tier`. Tier is HOW SENIOR, desk is WHICH FUNCTION. A
--  ticket can be tier 2 at the finance desk, and that is a coherent and common state.
--
--  Modelled as a column on the ticket rather than inferred from the assignee's role, because a
--  ticket can be AT the finance desk while nobody at finance has picked it up yet. Inferring the
--  desk from the current holder would make an unassigned transferred ticket indistinguishable from
--  an unassigned new one — which is precisely the ticket most likely to be lost.
--
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  WHY TIER 0 MAY HOLD A TRANSFERRED TICKET, HAVING BEEN BANNED FROM HOLDING ANY
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  The tier-0 rule exists to keep the AUTO-ASSIGNMENT engine from dropping general queue traffic on
--  finance. That reasoning does not extend to a human deliberately handing them a refund dispute.
--
--  A guard justified by one mechanism must not silently govern a different one. Auto-assign still
--  skips tier 0; explicit transfer does not.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

/*
 * Which function currently owns this ticket.
 *
 * 'support' | 'finance' | 'ops' | 'moderation'
 *
 * NOT NULL with a default so every existing ticket has a truthful value immediately: they were all
 * with support, because support was the only desk that existed. A nullable column here would mean
 * every read has to decide what null means, and different readers would decide differently.
 */
ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS handling_desk VARCHAR(20) NOT NULL DEFAULT 'support';

/*
 * Why it was moved here, and by whom — shown to the receiving desk at the top of the thread.
 *
 * The full history lives in ticket_assignment; this is the CURRENT reason, denormalised, because
 * the receiving agent needs it before they read anything else and should not have to open a
 * separate trail to find out why this landed on them.
 */
ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS desk_transfer_reason TEXT;

ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS desk_changed_at TIMESTAMPTZ;

/*
 * A closed vocabulary, enforced. A free-text desk column would accumulate 'finance', 'Finance' and
 * 'fin' within a month, and every screen that groups by desk would quietly show three desks.
 *
 * NOT VALID is deliberate: it applies the rule to all new and updated rows without a full table
 * scan on deploy. Existing rows all carry the default 'support', which satisfies it anyway — the
 * flag is here so this migration stays cheap as the table grows, not because the data is suspect.
 */
ALTER TABLE admin_schema.support_ticket
    DROP CONSTRAINT IF EXISTS chk_ticket_handling_desk;
ALTER TABLE admin_schema.support_ticket
    ADD CONSTRAINT chk_ticket_handling_desk
    CHECK (handling_desk IN ('support', 'finance', 'ops', 'moderation')) NOT VALID;

-- "What is on the finance desk right now" — the query the finance console opens with.
CREATE INDEX IF NOT EXISTS idx_support_ticket_desk
    ON admin_schema.support_ticket (handling_desk, status, priority);

COMMENT ON COLUMN admin_schema.support_ticket.handling_desk IS
    'WHICH FUNCTION owns this. Orthogonal to tier, which is HOW SENIOR. A tier-2 finance ticket is a normal state.';
COMMENT ON COLUMN admin_schema.support_ticket.desk_transfer_reason IS
    'Why it landed on this desk. Denormalised from ticket_assignment so the receiver sees it before reading the thread.';
