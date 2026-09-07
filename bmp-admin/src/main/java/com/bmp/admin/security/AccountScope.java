package com.bmp.admin.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * Who may act on WHOSE account. Session 65.
 *
 * <h2>The rule</h2>
 * <pre>
 *   support / support manager  →  customers only
 *   ops admin                  →  customers, salon-side people, support staff
 *   admin / main admin         →  anyone, including console admins
 * </pre>
 *
 * <h2>Why this is one class and not an {@code if} at each endpoint</h2>
 * There are four operations (change phone, change email, block, remove) and each needs the same
 * check. Written inline that is four copies of a security rule, and the fifth operation added next
 * year gets a fifth copy written from memory. One method, called by all of them, cannot drift.
 *
 * <p>It also means the rule is READABLE — someone asking "can support delete a salon owner?" reads
 * one file rather than reconstructing the answer from scattered annotations.
 */
public final class AccountScope {

    private AccountScope() {}

    /** Roles that belong to a person who books, and nothing more. */
    private static final Set<String> CUSTOMER_ROLES = Set.of("customer");

    /**
     * Roles on the salon side or the support side.
     *
     * Grouped together deliberately: from an account-administration point of view a salon owner and
     * a support agent are the same KIND of decision — somebody whose account being wrong affects
     * other people's work, not just their own bookings.
     */
    private static final Set<String> STAFF_ROLES =
            Set.of("salon_owner", "manager", "stylist", "support_agent", "support_lead", "finance_admin", "read_only");
    // NB the salon-side roles (salon_owner, manager, stylist) are NOT console roles and are not in
    // RoleHierarchy at all — they rank -1 there. That is why this set stays hand-written: it spans
    // two different worlds, and RoleHierarchy only knows one of them.

    /**
     * Console roles reached through THIS class, and why the list is short.
     *
     * <p>Session 65: a role is "an admin account" here if {@link RoleHierarchy} ranks it above the
     * desk — ops_admin (30), admin (40), super_admin (50). Derived rather than listed, so the rung
     * added next year is covered the day it is added rather than the day somebody notices.
     *
     * <p>Note what this does NOT do: it does not apply the strict-rank rule to these accounts. That
     * belongs to {@link StaffAccountScope}, which governs {@code admin_schema.bmp_staff} — the table
     * these people actually live in. This class governs {@code user_schema.users}, and a console
     * admin only appears here at all if they ALSO book haircuts, in which case it is their customer
     * row being edited, not their console account. Enforcing rank on a customer row would be
     * theatre: the row carries no console authority to protect.
     */
    private static boolean isConsoleAdminRole(String role) {
        return RoleHierarchy.rankOf(role) >= RoleHierarchy.rankOf(StaffPermission.OPS_ADMIN);
    }

    /**
     * Throws unless {@code caller} may administer an account whose role is {@code targetRole}.
     *
     * @param targetUserId used only for the self-check — see below. Pass the account being edited.
     */
    public static void requireCanManage(StaffPrincipal caller, String targetRole, UUID targetUserId) {
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        /*
         * NOBODY EDITS THEIR OWN ACCOUNT HERE — not even a super admin.
         *
         * Pointed at yourself this tool becomes a way to lift your own block or quietly rewrite the
         * identity every audit entry is attributed to.
         *
         * ── BE HONEST ABOUT WHAT THIS ACTUALLY CATCHES ───────────────────────────────────────
         * Very little, today. `caller.staffId()` is an admin_schema.bmp_staff id; `targetUserId` is
         * a user_schema.users id. They come from different tables, so they are never equal, and
         * this branch does not fire — a staff member's console account and their customer account
         * (if they book haircuts) are two different rows.
         *
         * It is kept because it costs nothing and becomes real the moment the two identities are
         * ever linked, which is a thing platforms do. The self-edit risk that exists RIGHT NOW is
         * on the staff side, and StaffAccountScope is where it is genuinely enforced — both ids
         * there are bmp_staff ids and the comparison means something.
         *
         * Checked FIRST, before the role comparison, so that "I am a super admin so I can do
         * anything" never reaches the point of being true about oneself.
         */
        if (targetUserId != null && targetUserId.equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can't administer your own account here. Ask another admin, or use your profile.");
        }

        String role = targetRole == null ? "" : targetRole.trim().toLowerCase();

        boolean allowed;
        if (CUSTOMER_ROLES.contains(role)) {
            allowed = caller.can(StaffPermission.ACCOUNT_MANAGE_CUSTOMER)
                    || caller.can(StaffPermission.ACCOUNT_MANAGE_STAFF)
                    || caller.can(StaffPermission.ACCOUNT_MANAGE_ANY);
        } else if (STAFF_ROLES.contains(role)) {
            allowed = caller.can(StaffPermission.ACCOUNT_MANAGE_STAFF)
                    || caller.can(StaffPermission.ACCOUNT_MANAGE_ANY);
        } else if (isConsoleAdminRole(role)) {
            allowed = caller.can(StaffPermission.ACCOUNT_MANAGE_ANY);
        } else {
            /*
             * An UNKNOWN role is refused, not waved through.
             *
             * A role added later that nobody remembered to classify would otherwise fall to the
             * most permissive branch and become editable by support. Failing closed means the
             * omission surfaces as "support cannot edit this" — a complaint — rather than as
             * silent over-permission, which is not.
             */
            allowed = caller.can(StaffPermission.ACCOUNT_MANAGE_ANY);
        }

        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, explain(role));
        }
    }

    /**
     * A refusal that says what would be needed.
     *
     * "Forbidden" tells an agent nothing and produces a support ticket about the support tool.
     * Naming the role that CAN do it turns a dead end into a next step.
     */
    private static String explain(String role) {
        if (isConsoleAdminRole(role)) {
            return RoleHierarchy.explain(role);
        }
        if (STAFF_ROLES.contains(role)) {
            return "This is a salon or staff account — an ops admin has to make this change.";
        }
        return "You don't have permission to change this account.";
    }
}
