package com.bmp.admin.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Who may administer a CONSOLE STAFF account. Session 65.
 *
 * <h2>Why this exists separately from {@link AccountScope}</h2>
 * They read like the same rule, and they are not the same accounts.
 *
 * <p>{@link AccountScope} governs {@code user_schema.users} — customers, salon owners, managers,
 * stylists. Everyone who signs in with a phone and an emailed code.
 *
 * <p>This class governs {@code admin_schema.bmp_staff} — the console's own people, who sign in with
 * a password and TOTP. Different table, different service, different screen (Staff management), and
 * a support agent's console account is NOT the same row as their customer account if they also
 * happen to book haircuts.
 *
 * <p>That distinction is easy to miss and expensive to get wrong: "ops admin can manage support
 * accounts" cannot be satisfied by the user endpoints at all, because support agents do not have
 * rows there. One class per table, each saying which table it means.
 *
 * <h2>The rule is RANK — see {@link RoleHierarchy}</h2>
 * <pre>
 *   main admin  (50)  →  every staff account below it
 *   admin       (40)  →  ops admin and below
 *   ops admin   (30)  →  support manager and below
 *   anyone else       →  nothing
 * </pre>
 *
 * <p>STRICTLY below, never equal. A peer managing a peer is the same hole as acting on yourself
 * with one extra step: one ops admin suspending another, or an admin re-credentialling another
 * admin, defeats the point of having a rung at all. Every ladder needs one it cannot reach from
 * below.
 */
public final class StaffAccountScope {

    private StaffAccountScope() {}

    /**
     * Throws unless {@code caller} may administer a staff account whose role is {@code targetRole}.
     *
     * @param targetStaffId the account being acted on, or null when creating a new one (there is
     *                      no id yet, and creating yourself is not a thing that can happen).
     */
    public static void requireCanManage(StaffPrincipal caller, String targetRole, UUID targetStaffId) {
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        /*
         * Self first, and this check REALLY FIRES here — unlike the equivalent in AccountScope,
         * where the caller's id is a bmp_staff id and the target's is a user id, so the two can
         * never be equal (see the note there).
         *
         * Here both sides are bmp_staff ids. Suspending yourself is unrecoverable without database
         * access, and changing your own role is self-promotion.
         */
        if (targetStaffId != null && targetStaffId.equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You can't change your own staff account. Ask another admin.");
        }

        String role = targetRole == null ? "" : targetRole.trim().toLowerCase();

        /*
         * ── RANK, not a hardcoded list. Session 65. ────────────────────────────────────────────
         *
         * This used to be "owner may do anything; ops may act on these four named roles". That
         * worked for two tiers and does not extend: adding ADMIN would have meant editing this
         * list, LeaveApprovalScope's list, AccountScope's list and the console's copy — four
         * places to keep in step, which is three too many.
         *
         * RoleHierarchy.canManage answers it once, by comparing ranks, and requires the actor to
         * STRICTLY outrank the target. That is what keeps a peer from managing a peer: one ops
         * admin cannot suspend another, and an admin cannot touch another admin.
         *
         * The owner still needs STAFF_MANAGE as well — rank alone would let an admin at rank 40
         * manage ops at 30, which is correct, but the permission is what says they may be on this
         * screen at all.
         */
        if (RoleHierarchy.canManage(caller.role(), role)
                && (caller.can(StaffPermission.STAFF_MANAGE)
                    || caller.can(StaffPermission.ACCOUNT_MANAGE_STAFF))) {
            return;
        }

        /*
         * Refusal names the boundary rather than saying "forbidden".
         *
         * An ops admin who is told "only the platform owner can do this" stops and asks the right
         * person. One who is told "forbidden" raises a ticket about the console.
         */
        if (caller.can(StaffPermission.ACCOUNT_MANAGE_STAFF) || caller.can(StaffPermission.STAFF_MANAGE)) {
            // They may manage SOMEBODY, just not this person. Name the rung that can.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, RoleHierarchy.explain(role));
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an ops admin or above can manage staff accounts.");
    }

    /**
     * Throws unless {@code caller} may edit somebody's EMPLOYMENT DETAILS. Session 65.
     *
     * <p>Separate from {@link #requireCanManage} because the two guard different things and giving
     * them the same answer would break one of them:
     *
     * <pre>
     *   requireCanManage        the ACCOUNT — suspend, offboard, create, re-credential, change role
     *   requireCanEditEmployment  the ROTA  — job title, shift note, reporting line, joined/exited
     * </pre>
     *
     * <p>A support manager needs the second and must not have the first. Answering both with
     * {@code account:manage_staff} would have forced a choice between a manager who cannot maintain
     * their own team's shift notes and a manager who can re-credential people.
     *
     * <p>The RANK rule is identical and deliberately so — strictly outrank, never a peer, never
     * yourself. Only the permission differs.
     */
    public static void requireCanEditEmployment(StaffPrincipal caller, String targetRole, UUID targetStaffId) {
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        if (targetStaffId != null && targetStaffId.equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You can't edit your own employment record. Ask someone above you.");
        }

        String role = targetRole == null ? "" : targetRole.trim().toLowerCase();
        if (RoleHierarchy.canManage(caller.role(), role) && caller.can(StaffPermission.TEAM_EDIT)) {
            return;
        }

        if (caller.can(StaffPermission.TEAM_EDIT)) {
            // They run a team, just not this person's. Name the rung that can.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, RoleHierarchy.explain(role));
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only a support manager or above can edit somebody's employment details.");
    }

    /**
     * Throws unless {@code caller} may CREATE an account with role {@code targetRole}. Session 65.
     *
     * <p>Separate from {@link #requireCanManage} because Darshan's call splits them: a support
     * manager hires onto their own desk and does NOT suspend or re-credential anybody.
     *
     * <p>Creating is the milder power only because of how the flow works — no password is ever set
     * by the creator, an activation code is minted and the account is unusable until the person
     * redeems it themselves. A creator therefore cannot log in as what they created. If that ever
     * changes, {@code STAFF_HIRE} must come off support_lead in the same commit.
     *
     * <p>No self-check: there is no account yet, and creating yourself is not a thing that happens.
     * Rank is the whole control — you may mint only a role you strictly outrank, so a manager
     * cannot mint a second manager and an ops admin cannot mint an owner.
     */
    public static void requireCanCreate(StaffPrincipal caller, String targetRole) {
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        String role = targetRole == null ? "" : targetRole.trim().toLowerCase();
        if (RoleHierarchy.canManage(caller.role(), role)
                && (caller.can(StaffPermission.STAFF_HIRE) || caller.can(StaffPermission.STAFF_MANAGE))) {
            return;
        }
        if (caller.can(StaffPermission.STAFF_HIRE) || caller.can(StaffPermission.STAFF_MANAGE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only create accounts below your own level. " + RoleHierarchy.explain(role));
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only a support manager or above can create staff accounts.");
    }

    /**
     * Throws unless {@code caller} may set {@code newStatus} on this account. Session 65.
     *
     * <h2>OFFBOARD and SUSPEND are different authorities, on the same column</h2>
     * <pre>
     *   offboarded  they left. Planned. The manager who runs their rota knows it happened.
     *   suspended   something is wrong NOW. An incident call, and it stays at ops and above.
     *   active      restoring access — the reverse of a suspension, so it needs the same authority.
     * </pre>
     *
     * Both write {@code status} and both revoke every session, so the MECHANISM is identical and
     * the authority is not. Splitting them here rather than at the endpoint means a future status
     * value cannot quietly inherit whichever rule happened to be nearest.
     */
    public static void requireCanSetStatus(StaffPrincipal caller, String targetRole,
                                            UUID targetStaffId, String newStatus) {
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        if (targetStaffId != null && targetStaffId.equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You can't change your own staff account. Ask another admin.");
        }

        boolean offboarding = "offboarded".equalsIgnoreCase(newStatus);
        if (offboarding
                && RoleHierarchy.canManage(caller.role(), targetRole)
                && caller.can(StaffPermission.STAFF_OFFBOARD)) {
            return;
        }

        /*
         * Anything that is not an offboarding — suspending, or restoring — falls through to the
         * full account rule. A manager holding only STAFF_OFFBOARD lands here and is refused, with
         * a message that says which of the two things they were trying to do.
         */
        try {
            requireCanManage(caller, targetRole, targetStaffId);
        } catch (ResponseStatusException e) {
            if (!offboarding && caller.can(StaffPermission.STAFF_OFFBOARD)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You can end-date somebody who has left, but suspending or restoring an "
                        + "account is an ops decision — it's about access right now, not staffing.");
            }
            throw e;
        }
    }

    /**
     * True when the caller may see the staff list at all.
     *
     * <p>Session 65 — a support manager sees it too, and {@link #visibleTo} is what makes that
     * safe: they see only the rungs below them. Without the filter this would have been the choice
     * between "a manager cannot see the accounts of the people they hire" and "a manager can
     * enumerate every console account there is, including the owner's".
     */
    public static boolean canView(StaffPrincipal caller) {
        return caller != null
                && (caller.can(StaffPermission.STAFF_MANAGE)
                    || caller.can(StaffPermission.ACCOUNT_MANAGE_STAFF)
                    || caller.can(StaffPermission.STAFF_HIRE));
    }

    /**
     * Should this caller see this account in the staff list? Session 65.
     *
     * <p>Rank, plus yourself. Your own row is always visible — a list that hides you reads as a
     * bug, and it tells you nothing you do not already know.
     *
     * <p>Uses the SAME comparison as {@link #requireCanManage}, deliberately: a list that shows
     * rows whose buttons all refuse is a list people learn to distrust. Filtering it with a second
     * hand-written rule is how the list and the action drift apart.
     */
    public static boolean visibleTo(StaffPrincipal caller, String targetRole, UUID targetStaffId) {
        if (caller == null) return false;
        if (targetStaffId != null && targetStaffId.equals(caller.staffId())) return true;
        return RoleHierarchy.canManage(caller.role(), targetRole);
    }

    /**
     * Roles this caller may CREATE. Session 65.
     *
     * <p>Derived from rank rather than listed, so it cannot drift from {@link #requireCanManage}:
     * if you may manage a role, you may create one. An ops admin sees the desk roles; an admin also
     * sees ops_admin; only the owner sees admin. Nobody can create their own rank or above, which
     * is what stops a promotion being self-served.
     */
    public static java.util.List<String> creatableBy(StaffPrincipal caller) {
        if (caller == null) return java.util.List.of();
        return java.util.stream.Stream.of(
                        StaffPermission.SUPER_ADMIN, RoleHierarchy.ADMIN, StaffPermission.OPS_ADMIN,
                        StaffPermission.SUPPORT_LEAD, StaffPermission.SUPPORT_AGENT,
                        StaffPermission.FINANCE_ADMIN, StaffPermission.READ_ONLY)
                .filter(r -> RoleHierarchy.canManage(caller.role(), r))
                .toList();
    }
}
