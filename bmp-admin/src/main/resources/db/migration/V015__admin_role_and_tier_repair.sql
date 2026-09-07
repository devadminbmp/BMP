-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- V015 — the ADMIN rung, and repairing the tier every console-created account was given. Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── 1. THE NEW ROLE NEEDS NO SCHEMA CHANGE, AND THAT IS WORTH SAYING OUT LOUD ──────────────────
-- Darshan's hierarchy is MAIN ADMIN → ADMIN → OPERATIONS ADMIN → SUPPORT MANAGER → SUPPORT.
-- `admin` is the new middle rung (RoleHierarchy, rank 40).
--
-- `bmp_staff.role` is VARCHAR(20) with NO CHECK constraint (V002), so the string simply fits.
-- Deliberately NOT adding a CHECK now: the roles are enforced in Java in three places that all
-- agree (RoleHierarchy.RANKS, StaffPermission.ROLE_PERMISSIONS, the Bean Validation pattern on
-- CreateEmployeeRequest), and a fourth copy in the database is a fourth thing to keep in step —
-- with the worst failure mode of the four, because a rejected INSERT surfaces as a 500 at 2am
-- rather than a 400 at the door.
--
-- ── 2. THE ACTUAL BUG THIS MIGRATION FIXES ─────────────────────────────────────────────────────
-- V009 added `tier SMALLINT NOT NULL DEFAULT 1` and backfilled the rows that existed then. The
-- BmpStaff constructor never set it, so every account created through the console SINCE has been
-- born at tier 1 — the support-agent queue — whatever its role.
--
-- That is not cosmetic. BmpStaffRepository.findNextAssignee selects:
--
--     WHERE tier = :tier AND tier > 0 AND status = 'active' AND accepting_tickets = true
--
-- so a finance_admin or read_only account created via the API was silently eligible to be
-- auto-assigned live customer support tickets, and a support_lead sat in the tier-1 pool instead
-- of the tier-2 one they escalate to.
--
-- A DEFAULT is not a rule. The constructor now sets tier from the role (SupportTier.forRole);
-- this repairs the rows written before it did.
--
-- ── WHY IT IS SAFE TO OVERWRITE tier HERE ──────────────────────────────────────────────────────
-- Nothing in the console lets a human choose a tier — there is no endpoint, no field, no screen.
-- Every value in the column was therefore either V009's backfill (which this reproduces exactly
-- for the roles V009 knew about) or the accidental default. There is no hand-set value to lose.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

-- Mirrors SupportTier.forRole exactly. If you change one, change the other — the Java version is
-- what governs new rows; this is a one-time repair of old ones.
UPDATE admin_schema.bmp_staff
   SET tier = CASE lower(role)
                  WHEN 'super_admin'   THEN 4
                  WHEN 'ops_admin'     THEN 3
                  WHEN 'support_lead'  THEN 2
                  WHEN 'support_agent' THEN 1
                  -- admin is MANAGERIAL: rank 40, above ops, and it works no tickets.
                  -- finance_admin and read_only were never on the ladder.
                  ELSE 0
              END,
       updated_at = now()
 WHERE tier IS DISTINCT FROM CASE lower(role)
                  WHEN 'super_admin'   THEN 4
                  WHEN 'ops_admin'     THEN 3
                  WHEN 'support_lead'  THEN 2
                  WHEN 'support_agent' THEN 1
                  ELSE 0
              END;

/*
 * A staff member off the ladder must not be sitting in a queue as "accepting tickets" either.
 *
 * accepting_tickets is a real toggle a human sets (Team → availability), so this does NOT reset it
 * for everyone — only for the rows that just moved to tier 0, where the flag now means nothing.
 * Leaving it true would be harmless today (findNextAssignee also requires tier > 0) and misleading
 * on the Team screen, which shows availability as a status.
 */
UPDATE admin_schema.bmp_staff
   SET accepting_tickets = false,
       updated_at = now()
 WHERE tier = 0 AND accepting_tickets = true;

-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- 3. THE AUTHORITY MATRIX — a new role with no rows here can do NOTHING
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- AuthorityService.check reads admin_schema.authority_limit by (action_type, role) and, finding
-- nothing, returns FORBIDDEN:
--
--     "No authority row for action=… role=… — refusing."
--
-- That default is right — a role nobody has thought about should not be able to move money — and
-- it means adding `admin` to the Java hierarchy alone would have produced a rung with real power
-- over PEOPLE (hiring, leave, suspension) and no power at all over MONEY. An admin would have been
-- refused a ₹1 refund with "your role can't do this", which reads as a bug and is a missing row.
--
-- ── WHERE THE CEILINGS COME FROM ───────────────────────────────────────────────────────────────
-- Above ops, below the owner, in every case — that is what the rung MEANS. Where ops is already
-- unbounded (NULL) admin is unbounded too: there is no "more than unlimited", and giving admin a
-- number there would put it BELOW ops, inverting the hierarchy for that action.
--
-- ── WHAT THIS DELIBERATELY DOES NOT CHANGE ─────────────────────────────────────────────────────
-- The existing escalation ROUTING. Ops still escalates straight to super_admin, exactly as before.
-- Re-pointing ops → admin is a real business decision about who gets woken up, it is Darshan's to
-- make, and doing it inside a migration labelled "add a role" is how routing changes surprise
-- people. Adding rows changes nobody's existing path; re-pointing changes everybody's.
--
-- The consequence, stated plainly so it is a choice rather than an oversight: until that routing
-- changes, an admin can ACT up to their own ceiling but nothing escalates TO them.

INSERT INTO admin_schema.authority_limit
    (id, action_type, role, max_value_paise, approver_role, step_order, requires_ticket)
VALUES
    -- Money actions: a real band between ops and the owner.
    -- coupon.issue     ops ₹10,000  →  admin ₹50,000   →  owner unbounded
    ('610a1937-3b3a-5bd0-ba4d-706665cd556f', 'coupon.issue',  'admin',  5000000, 'super_admin', 4, false),
    -- refund.issue     ops ₹50,000  →  admin ₹2,00,000 →  owner unbounded
    ('006ce58a-b123-5d77-b2fc-1f0e57a12e60', 'refund.issue',  'admin', 20000000, 'super_admin', 4, false),
    -- wallet.credit    ops ₹10,000  →  admin ₹50,000   →  owner unbounded. Tighter than coupons
    -- for the same reason V010 gives: wallet credit is closer to cash than a discount code is.
    ('198030d3-fec5-5989-a37e-deb8a0e487ea', 'wallet.credit', 'admin',  5000000, 'super_admin', 4, false),

    -- Actions where ops is ALREADY unbounded. Admin matches; there is nothing above unbounded, so
    -- a number here would rank admin below ops and invert the ladder for that action.
    ('8d61f1c2-2753-584e-a323-2dad3b862f52', 'booking.waive_fee', 'admin', NULL, NULL, 4, false),
    ('4cac773b-a4e6-52a5-905e-837905507487', 'salon.suspend',     'admin', NULL, NULL, 3, false),
    ('de0457b2-3edd-5c02-a2a3-62946e7cc611', 'stylist.suspend',   'admin', NULL, NULL, 3, false),
    -- user.anonymise is IRREVERSIBLE and ops already holds it unbounded. Admin outranks ops, so
    -- withholding it here would be the one place the ladder runs backwards.
    ('e0ee5c2d-061d-5165-896c-2b7b39a0901a', 'user.anonymise',    'admin', NULL, NULL, 3, false),
    ('c6e9b42c-8ea8-51a2-a876-e23d7abe5e22', 'commission.adjust', 'admin', NULL, 'super_admin', 2, false)
ON CONFLICT (action_type, role) DO NOTHING;

/*
 * Push the owner's step_order above the new rung so the matrix screen reads top-to-bottom in
 * authority order.
 *
 * SAFE because step_order is DISPLAY ONLY: the sole consumer is
 * findByActionTypeAndActiveTrueOrderByStepOrderAsc, which sorts the matrix view. Authority itself
 * is decided by (action_type, role) — the unique index — and escalation by approver_role. There is
 * no unique constraint on step_order and no comparison against it anywhere.
 */
UPDATE admin_schema.authority_limit
   SET step_order = step_order + 1,
       updated_at = now()
 WHERE role = 'super_admin'
   AND action_type IN ('coupon.issue', 'refund.issue', 'wallet.credit', 'booking.waive_fee',
                       'salon.suspend', 'stylist.suspend', 'user.anonymise', 'commission.adjust')
   AND step_order <= (
        SELECT max(step_order) FROM admin_schema.authority_limit a2
         WHERE a2.action_type = authority_limit.action_type AND a2.role = 'admin'
   );

COMMENT ON COLUMN admin_schema.bmp_staff.tier IS
    'SUPPORT ESCALATION tier, 0-4. NOT the management rank — see RoleHierarchy (Session 65): '
    'admin ranks ABOVE ops_admin yet sits at tier 0, because it manages people and works no '
    'tickets. 0 means off the ladder entirely (admin, finance_admin, read_only). Written from the '
    'role by SupportTier.forRole at account creation; the column DEFAULT is a fallback, not the rule.';
