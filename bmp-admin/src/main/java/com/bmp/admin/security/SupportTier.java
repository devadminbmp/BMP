package com.bmp.admin.security;

import java.util.Map;

/**
 * Role → SUPPORT TIER. The escalation ladder, not the management ladder. Session 65.
 *
 * <pre>
 *   4  super_admin      last stop for an escalation
 *   3  ops_admin
 *   2  support_lead     (SUPPORT MANAGER)
 *   1  support_agent    (SUPPORT) — where tickets normally land
 *   0  admin, finance_admin, read_only — off the ladder entirely
 * </pre>
 *
 * <h2>Read {@link RoleHierarchy} first — these are two different ladders</h2>
 * TIER answers <i>"who works this ticket"</i>. RANK answers <i>"who may manage whom"</i>. They look
 * similar for the support roles and diverge immediately outside them:
 *
 * <ul>
 *   <li><b>admin</b> ranks 40 — above ops — and sits at tier <b>0</b>. Darshan's call, Session 65:
 *       the ADMIN role is managerial. It hires, approves leave, edits salons and signs off refunds,
 *       and it never gets a chat ticket dropped in its lap. Giving it tier 3 or 4 would have put a
 *       manager into the round-robin the moment one was created.</li>
 *   <li><b>finance_admin</b> ranks 0 and is tier 0, yet does work no support agent can do. Rank 0
 *       means "manages nobody"; tier 0 means "takes no tickets". Neither means "junior".</li>
 * </ul>
 *
 * <h2>Why this class exists at all — a real bug it fixes</h2>
 * {@code bmp_staff.tier} is {@code NOT NULL DEFAULT 1} (V009), and the {@code BmpStaff} constructor
 * never set it. So EVERY account created through the console — finance, read-only, and now admin —
 * was born at tier 1, which is the support-agent queue.
 *
 * <p>That is not cosmetic. {@code BmpStaffRepository.findNextAssignee} selects on
 * {@code tier = :tier AND tier > 0 AND accepting_tickets}, so a finance admin created via the API
 * was silently eligible to be auto-assigned customer support tickets. V009's backfill set the
 * correct tiers for the rows that existed <i>then</i>; nothing kept new rows honest afterwards.
 * A default is not a rule.
 *
 * <p>V015 repairs the rows already written that way. This class is what stops it recurring: one
 * mapping, applied at construction, used by the approval trail too.
 */
public final class SupportTier {

    private SupportTier() {}

    /** Off the escalation ladder. Not a queue, not a rank — just "no tickets". */
    public static final short NONE = 0;

    private static final Map<String, Short> TIERS = Map.of(
            StaffPermission.SUPER_ADMIN,   (short) 4,
            StaffPermission.OPS_ADMIN,     (short) 3,
            StaffPermission.SUPPORT_LEAD,  (short) 2,
            StaffPermission.SUPPORT_AGENT, (short) 1,
            RoleHierarchy.ADMIN,           NONE,
            StaffPermission.FINANCE_ADMIN, NONE,
            StaffPermission.READ_ONLY,     NONE);

    /**
     * The tier this role works at, or {@link #NONE}.
     *
     * <p>An unrecognised role gets {@code NONE}, which fails CLOSED in the useful direction: a role
     * somebody adds later and forgets to map here receives no tickets. Someone notices ("why is
     * nothing reaching them?") and it gets fixed. The opposite default — dropping live customer
     * tickets on an unmapped role — is noticed by the customer, days later.
     */
    public static short forRole(String role) {
        if (role == null) return NONE;
        return TIERS.getOrDefault(role.trim().toLowerCase(), NONE);
    }
}
