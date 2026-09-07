package com.bmp.admin.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Who may approve WHOSE leave. Session 65.
 *
 * <h2>The rule is RANK — see {@link RoleHierarchy}</h2>
 * You may decide the leave of anyone you STRICTLY outrank.
 *
 * <pre>
 *   support, finance, read-only  →  support manager, ops admin, admin, or the main admin
 *   support manager              →  ops admin, admin, or the main admin
 *   ops admin                    →  admin or the main admin
 *   admin                        →  the main admin only
 *   the main admin               →  a SECOND main admin (see below)
 * </pre>
 *
 * <h2>The main admin's own leave</h2>
 * Nobody outranks rank 50, so with exactly one main admin their leave has no approver and the
 * request sits pending. That is the honest outcome and Darshan's explicit call in Session 65: the
 * alternatives were auto-approving the owner's leave (an approval that approves itself) or
 * special-casing equality for super_admin (a peer approving a peer, the exact hole this class
 * exists to close). The answer is a second main admin, and the pending request is the prompt to
 * create one.
 *
 * <h2>Why it is "one rung above OR HIGHER", not exactly one rung</h2>
 * A strict single rung is tidier on a diagram and worse in an office. The person one level up is
 * also the person most likely to be away — and leave that cannot be approved because the approver
 * is on leave is how people stop asking and simply do not come in. Anyone senior enough can always
 * step in.
 *
 * <h2>What it never allows</h2>
 * <ul>
 *   <li><b>Your own.</b> Checked first, and separately from the role rule, because a super admin
 *       passes every role check — including the one on themselves.</li>
 *   <li><b>A peer's.</b> One support agent approving another's leave is not an approval; it is two
 *       people agreeing with each other. Same for two ops admins.</li>
 * </ul>
 *
 * <p>Before this, {@code StaffLeaveService.decide} allowed ops_admin and super_admin only. That
 * left a support manager — a role whose entire purpose is running the desk — unable to approve a
 * day off for somebody on their own team, and it let one ops admin approve another's.
 */
public final class LeaveApprovalScope {

    private LeaveApprovalScope() {}

    /**
     * True when {@code caller} may decide leave belonging to somebody in {@code targetRole}.
     *
     * <p>Separate from {@link #requireCanDecide} so the pending QUEUE can be filtered with the same
     * rule that guards the action. A queue showing rows you cannot act on is a queue people learn
     * to ignore — and filtering it with a second, hand-written copy of this rule is how the two
     * drift apart.
     */
    public static boolean canDecide(StaffPrincipal caller, String targetRole, UUID targetStaffId) {
        if (caller == null) return false;

        // Your own leave, at any rank. Not a role question at all.
        if (targetStaffId != null && targetStaffId.equals(caller.staffId())) return false;

        /*
         * ── ONE RANK RULE, shared with staff administration. Session 65. ───────────────────────
         *
         * This was a hand-written ladder: owner decides anything, ops decides the desk and leads,
         * a lead decides the desk. Correct, and a fourth copy of the same comparison — after
         * StaffAccountScope, AccountScope and the console. Adding the ADMIN tier would have meant
         * editing all four and hoping.
         *
         * RoleHierarchy.canManage says it once: you may decide the leave of anyone you outrank.
         * STRICTLY outrank, so ops still cannot approve ops and an admin cannot approve an admin —
         * the property the old code got right and which a rank comparison preserves for free.
         *
         * The owner's own leave has no approver above it. That is a real gap in any hierarchy with
         * a top, and naming it beats pretending otherwise: another super_admin can decide it,
         * which is why rank 50 vs 50 correctly refuses and a SECOND owner is the answer.
         */
        return RoleHierarchy.canManage(caller.role(), targetRole);
    }

    /** Throws with a message naming who CAN, so the answer is a next step rather than a dead end. */
    public static void requireCanDecide(StaffPrincipal caller, String targetRole, UUID targetStaffId) {
        if (canDecide(caller, targetRole, targetStaffId)) return;

        if (caller != null && targetStaffId != null && targetStaffId.equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can't approve your own leave — ask someone above you.");
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You can't decide this person's leave. " + RoleHierarchy.explain(targetRole));
    }

    /** True when this person may see a pending-leave queue at all. */
    public static boolean canDecideAnything(StaffPrincipal caller) {
        if (caller == null) return false;
        // Anyone who outranks the bottom of the ladder can decide somebody's leave.
        return RoleHierarchy.rankOf(caller.role()) > RoleHierarchy.rankOf(StaffPermission.SUPPORT_AGENT);
    }
}
