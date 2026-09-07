package com.bmp.admin.security;

import java.util.Map;

/**
 * The management hierarchy: who outranks whom. Session 65.
 *
 * <pre>
 *   MAIN ADMIN        super_admin     50
 *   ADMIN             admin           40
 *   OPERATIONS ADMIN  ops_admin       30
 *   SUPPORT MANAGER   support_lead    20
 *   SUPPORT           support_agent   10
 *
 *   off the ladder    finance_admin    0
 *                     read_only        0
 * </pre>
 *
 * <h2>RANK is not TIER, and conflating them would break ticket assignment</h2>
 * {@code bmp_staff.tier} (V009) is the SUPPORT ESCALATION LADDER: TicketAssignmentService assigns
 * a ticket to somebody at exactly the ticket's tier, and escalation moves it up one. It answers
 * "who works this ticket".
 *
 * <p>RANK answers a different question — "who may manage whom". They overlap for the support roles
 * and diverge everywhere else: an ADMIN outranks an ops admin but takes no tickets at all, and
 * finance sits at rank 0 while doing work no support agent can do.
 *
 * <p>Squeezing ADMIN into the tier column would have meant renumbering super_admin from 4 to 5,
 * which changes the meaning of every existing tier value and every comparison against it. Two
 * concepts, two fields, no renumbering.
 *
 * <h2>Rank 0 means "manages nobody", NOT "is managed by everybody"</h2>
 * finance_admin and read_only are outside the management chain. They cannot manage anyone — and
 * because {@link #canManage} requires the actor to STRICTLY outrank the target, two rank-0 roles
 * cannot manage each other either. Only ops and above can act on them.
 *
 * <h2>Strictly greater, never equal</h2>
 * A peer may not manage a peer. One ops admin suspending another, or approving their own leave one
 * step removed, is the same hole as acting on yourself — and it is the hole that existed in leave
 * approval before Session 65.
 */
public final class RoleHierarchy {

    private RoleHierarchy() {}

    /** Session 65 — the tier your spec calls ADMIN. Between ops and the platform owner. */
    public static final String ADMIN = "admin";

    private static final Map<String, Integer> RANKS = Map.of(
            StaffPermission.SUPER_ADMIN,   50,
            ADMIN,                         40,
            StaffPermission.OPS_ADMIN,     30,
            StaffPermission.SUPPORT_LEAD,  20,
            StaffPermission.SUPPORT_AGENT, 10,
            StaffPermission.FINANCE_ADMIN,  0,
            StaffPermission.READ_ONLY,      0);

    /**
     * Where this role sits. Unknown roles rank -1, BELOW everything.
     *
     * <p>Fails closed by construction: a role somebody adds next year and forgets to rank here
     * cannot manage anybody (its rank beats nothing) and is itself manageable only by roles that
     * outrank -1, which is all of them. The failure mode is "this new role has no authority",
     * which somebody notices and reports — not "this new role can do anything", which nobody does.
     */
    public static int rankOf(String role) {
        if (role == null) return -1;
        return RANKS.getOrDefault(role.trim().toLowerCase(), -1);
    }

    /**
     * May {@code actorRole} manage somebody whose role is {@code targetRole}?
     *
     * <p>STRICTLY greater. Equality is refused on purpose — see the class javadoc.
     *
     * <p>This answers only the ROLE question. Whether the actor is acting on THEMSELVES is a
     * separate check, and it has to be, because a super admin outranks every role including their
     * own: rank alone would happily let the owner suspend their own account.
     */
    public static boolean canManage(String actorRole, String targetRole) {
        int actor = rankOf(actorRole);
        int target = rankOf(targetRole);
        if (actor < 0) return false;          // unknown actor manages nobody

        /*
         * ── OFF-LADDER ROLES NEED OPS OR ABOVE. Session 65, and this is a bug fix. ─────────────
         *
         * finance_admin and read_only rank 0. The class javadoc says rank 0 means "manages
         * nobody" — and a plain `actor > target` ALSO makes them managed by everybody, including
         * a support manager at rank 20.
         *
         * That is wrong and it is not a small wrong. A support manager could have created,
         * suspended or re-credentialled a FINANCE ADMIN — the role that approves refunds — because
         * finance sits at 0 on a ladder finance is not on. Rank 0 means "outside the management
         * chain", not "junior to everyone in it".
         *
         * The floor is OPS_ADMIN: the first rung whose job is the organisation rather than the
         * desk. An ops admin, admin or the owner may administer finance and read-only accounts;
         * the support ladder may not, at any level.
         *
         * Written as an explicit branch rather than by inventing a rank for finance, because any
         * number would be a lie in one direction or the other — above support implies finance
         * manages agents (it does not), below support implies agents outrank finance (they do not).
         * Finance is beside the ladder, and a comparison cannot express "beside".
         */
        if (target == 0 && actor < rankOf(OPS_ADMIN_ROLE)) return false;

        return actor > target;
    }

    /**
     * Kept as a private constant so {@link #canManage} does not depend on StaffPermission, which
     * depends on this class. A static-init cycle between the two would resolve to nulls at class
     * load and fail as "nobody can manage anybody" — silently, and only in production.
     */
    private static final String OPS_ADMIN_ROLE = "ops_admin";

    /** True for the roles that sit on the management ladder at all. */
    public static boolean isStaffRole(String role) {
        return RANKS.containsKey(role == null ? "" : role.trim().toLowerCase());
    }

    /**
     * A refusal that names the rung needed, rather than saying "forbidden".
     *
     * <p>"You do not have permission" makes somebody guess, and the usual guess is "ask anyone
     * senior", which sends the request to the wrong person. Naming the role turns a dead end into
     * a next step.
     */
    public static String explain(String targetRole) {
        int target = rankOf(targetRole);
        if (target >= 50) return "Only the platform owner can manage this account.";
        if (target >= 40) return "Only the platform owner can manage an admin.";
        if (target >= 30) return "An admin or the platform owner has to make this change.";
        if (target >= 20) return "An ops admin or above has to make this change.";
        return "You don't have permission to manage this account.";
    }

    /** Human label for a role, for messages and the console. */
    public static String label(String role) {
        String r = role == null ? "" : role.trim().toLowerCase();
        return switch (r) {
            case StaffPermission.SUPER_ADMIN   -> "Main admin";
            case ADMIN                         -> "Admin";
            case StaffPermission.OPS_ADMIN     -> "Operations admin";
            case StaffPermission.SUPPORT_LEAD  -> "Support manager";
            case StaffPermission.SUPPORT_AGENT -> "Support";
            case StaffPermission.FINANCE_ADMIN -> "Finance";
            case StaffPermission.READ_ONLY     -> "Read only";
            default -> role == null ? "unknown" : role;
        };
    }
}
