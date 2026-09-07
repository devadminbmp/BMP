-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- V017 — ADMIN becomes a real rung in the escalation chain. Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- Darshan's call, Session 65: ops escalates to ADMIN, not straight to the platform owner.
--
--   before:   support → support manager → ops → MAIN ADMIN
--   after:    support → support manager → ops → ADMIN → MAIN ADMIN
--
-- ── WHY THIS IS A SEPARATE MIGRATION FROM V015 ─────────────────────────────────────────────────
-- V015 added `admin` rows to the authority matrix. That was purely ADDITIVE: it gave the new rung
-- powers of its own and changed nobody's existing path. Re-pointing an approver_role changes where
-- EVERY over-limit request goes, which is a decision about who gets woken up at 2am. Bundling it
-- into a migration labelled "add a role" is how routing changes surprise people six weeks later.
--
-- So V015 said, in its own comment: "until that routing changes, an admin can ACT up to their own
-- ceiling but nothing escalates TO them." This is that change, made deliberately and on its own.
--
-- ── WHAT WOULD HAVE HAPPENED WITHOUT IT ────────────────────────────────────────────────────────
-- ApprovalRequestService.mustBePendingAndMine allows a decision only when
--
--     request.currentApproverRole == caller.role  (or the caller is super_admin)
--
-- With nothing pointing at `admin`, an admin's Approvals screen would have been permanently empty
-- while their nav item promised otherwise. A rung nobody escalates to is a title, not a role.
--
-- ── SCOPE: only where ops is CAPPED ────────────────────────────────────────────────────────────
-- Rows where ops_admin already has a NULL (unbounded) ceiling never escalate at all — the request
-- is simply allowed — so their approver_role is dead metadata. Touching it would produce a diff
-- that changes no behaviour, which is the kind of change that makes a future reader hunt for a
-- consequence that does not exist. Only the three capped money actions move.
--
-- ── WHAT DOES NOT CHANGE ───────────────────────────────────────────────────────────────────────
-- The MAIN ADMIN can still decide anything at any time — `mustBePendingAndMine` grants super_admin
-- a blanket override on purpose, so a request stuck behind an absent admin is never stuck for long.
-- This adds a rung; it does not remove the owner's reach.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

UPDATE admin_schema.authority_limit
   SET approver_role = 'admin',
       updated_at = now()
 WHERE role = 'ops_admin'
   AND approver_role = 'super_admin'
   /*
    * Only where a ceiling actually exists. NULL means unbounded, which means the request is
    * approved outright and the approver is never consulted — see AuthorityService.check, where a
    * null ceiling returns ALLOWED before approver_role is ever read.
    */
   AND max_value_paise IS NOT NULL;

/*
 * ── THE INVARIANT THAT MAKES ESCALATION WORTH DOING ────────────────────────────────────────────
 *
 * The band you escalate INTO must be higher than the one you escalated OUT of. Otherwise the
 * request arrives somewhere that can approve even less than the person who raised it, and the
 * escalation makes approval LESS likely rather than more — a failure that looks like a stuck queue
 * rather than a misconfiguration.
 *
 * AuthorityService.updateLimit already refuses to save a ceiling that breaks this. Nothing checked
 * it after a MIGRATION rewrote approver_role, and this is that check: it fails the migration loudly
 * rather than leaving a chain that quietly routes upward into a lower ceiling.
 */
DO $$
DECLARE
    broken text;
BEGIN
    SELECT string_agg(
               a.action_type || ': ' || a.role || ' (' || a.max_value_paise
               || ') escalates to ' || a.approver_role || ' (' || up.max_value_paise || ')',
               E'\n  ')
      INTO broken
      FROM admin_schema.authority_limit a
      JOIN admin_schema.authority_limit up
        ON up.action_type = a.action_type AND up.role = a.approver_role
     WHERE a.approver_role IS NOT NULL
       AND a.max_value_paise IS NOT NULL
       AND up.max_value_paise IS NOT NULL
       AND up.max_value_paise < a.max_value_paise;

    IF broken IS NOT NULL THEN
        RAISE EXCEPTION E'Escalation chain routes upward into a LOWER ceiling:\n  %\n\nNothing has been changed. Raise the higher band first.', broken;
    END IF;
END $$;

COMMENT ON COLUMN admin_schema.authority_limit.approver_role IS
    'Who decides when this role is over its ceiling. Session 65: ops_admin now escalates to admin '
    'rather than straight to super_admin, so the ADMIN rung absorbs work before it reaches the '
    'platform owner. NULL ceilings never consult an approver at all — the request is allowed.';
