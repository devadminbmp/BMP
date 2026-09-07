package com.bmp.admin.security;

import java.util.Map;
import java.util.Set;

/**
 * What each staff role may do.
 *
 * <p><b>Why a fixed role → permission map, not a per-user matrix.</b> Per-user permissions sound
 * flexible and become impossible to reason about: after a year nobody can answer "who can issue
 * refunds?" without a database query, and access reviews stop happening. Five roles that a human
 * can hold in their head is worth more than arbitrary granularity — and when a role genuinely
 * doesn't fit, the answer is a sixth role, decided deliberately.
 *
 * <p><b>Least privilege is the default.</b> SUPPORT — the largest team, and the one handling
 * angry customers under time pressure — cannot suspend salons, cannot change platform settings,
 * and cannot create staff accounts. Those are OPS and SUPERADMIN powers. The point isn't
 * distrust; it's that a mistake made at speed should have a small blast radius.
 *
 * <p>Role strings match {@code admin_schema.bmp_staff.role} as created in V002.
 */
public final class StaffPermission {
    private StaffPermission() {}

    // Roles (V002 values — do not rename without a migration)
    public static final String SUPER_ADMIN = "super_admin";

    /**
     * Session 65 — the ADMIN tier, between the platform owner and ops.
     *
     * <p>Everything an ops admin can do, plus authority OVER ops admins: an admin may create,
     * suspend and re-credential an ops admin, and decide their leave. What an admin cannot do is
     * touch the platform owner, or grant themselves {@code account:manage_any} — the rungs above
     * are what the hierarchy exists to protect.
     *
     * <p>Deliberately NOT given STAFF_MANAGE, which remains the owner's. See RoleHierarchy for how
     * rank is compared, and why rank is separate from the support tier.
     */
    public static final String ADMIN = RoleHierarchy.ADMIN;

    public static final String OPS_ADMIN = "ops_admin";
    public static final String SUPPORT_AGENT = "support_agent";
    /**
     * Session 57 — the rung that was missing.
     *
     * <p>BMP had agent, ops and owner. With nothing in between, support's only escalation was ops,
     * so a role meant for policy and salon standing spent its day on individual complaints. Every
     * comparable desk has a lead tier; this is it.
     *
     * <p>A lead is an agent who can also take escalations, see the whole team's queue and reassign
     * within it. Deliberately NOT given DATA_REQUEST_FULFIL or SETTINGS_MANAGE — seniority in
     * support is not seniority over the platform.
     */
    public static final String SUPPORT_LEAD = "support_lead";
    public static final String FINANCE_ADMIN = "finance_admin";
    public static final String READ_ONLY = "read_only";

    // Permissions
    public static final String SALON_REVIEW = "salon:review";           // approve / reject / suspend
    public static final String SALON_VIEW = "salon:view";
    public static final String USER_VIEW = "user:view";
    public static final String USER_PII_REVEAL = "user:pii_reveal";     // see an unmasked phone/email
    public static final String USER_DEACTIVATE = "user:deactivate";
    public static final String BOOKING_VIEW = "booking:view";
    public static final String REFUND_ISSUE = "refund:issue";
    public static final String TICKET_VIEW = "ticket:view";
    public static final String TICKET_WORK = "ticket:work";             // reply, assign, resolve
    public static final String CONTENT_MODERATE = "content:moderate";
    public static final String DATA_REQUEST_VIEW = "data_request:view";
    public static final String DATA_REQUEST_FULFIL = "data_request:fulfil";
    public static final String STAFF_MANAGE = "staff:manage";           // create/suspend staff
    public static final String SETTINGS_MANAGE = "settings:manage";     // feature flags, kill switch
    public static final String AUDIT_VIEW = "audit:view";
    /** Session 57 — hand a ticket up the ladder. Everyone on the ladder has it. */
    public static final String TICKET_ESCALATE = "ticket:escalate";
    /** Session 57 — see and reassign the whole team's queue, not just your own tickets. */
    public static final String TICKET_QUEUE_MANAGE = "ticket:queue_manage";

    /**
     * Edit somebody's EMPLOYMENT DETAILS — job title, shift note, reporting line, dates. Session 65.
     *
     * <h2>Why this is not {@code staff:manage} or {@code account:manage_staff}</h2>
     * Those two are about the ACCOUNT: credentials, status, role, suspension. This is about the
     * ROTA. A support manager writing "back at 2pm" against one of their agents is running a desk;
     * they must not be able to suspend that agent, change their role, or reissue their password.
     *
     * <p>Collapsing the two would have forced a choice between "a support manager cannot maintain
     * their own team's shift notes" and "a support manager can re-credential people" — and the
     * second is how a rota screen becomes a privilege escalation.
     *
     * <p>Rank still applies on top: you may edit the employment details only of somebody you
     * strictly outrank. The permission says you belong on the screen; the rank says on whom.
     */
    public static final String TEAM_EDIT = "team:edit";

    /**
     * HIRE onto your own team: create an account for somebody you outrank. Session 65.
     *
     * <h2>Why creating is separable from suspending, and safer</h2>
     * Darshan asked for a support manager who can "create support profile". The instinct is to give
     * them {@code account:manage_staff} and be done — and that would also hand them SUSPEND and
     * REISSUE, which are security actions taken during an incident, not staffing ones.
     *
     * <p>Creating is genuinely the milder power HERE, and only because of how the flow is built:
     * {@code createEmployee} never sets a password. It mints a one-time activation code the person
     * redeems themselves, and the account is {@code invited} — unusable — until they do. So a
     * manager who creates an account cannot log in as it, which is the thing that would make
     * "create" equivalent to "impersonate".
     *
     * <p>If that flow ever changes so that a creator sets a password, this permission has to be
     * withdrawn from support_lead in the same commit. The safety is a property of the flow, not of
     * the word "create".
     *
     * <p>Rank still applies: you may create only a role you strictly outrank, so a manager can mint
     * an agent and never a second manager, an ops admin or a finance admin.
     */
    public static final String STAFF_HIRE = "staff:hire";

    /**
     * END-DATE somebody: mark them offboarded when they leave. Session 65.
     *
     * <h2>Deliberately NOT the same as suspend</h2>
     * Both write {@code bmp_staff.status} and they are different events:
     *
     * <ul>
     *   <li><b>offboarded</b> — they resigned, their last day has passed. Planned, expected, and
     *       the manager who runs their rota is the person who knows it happened.</li>
     *   <li><b>suspended</b> — something is wrong RIGHT NOW and their access must stop. An incident
     *       decision, often about the person, and it stays with ops and above.</li>
     * </ul>
     *
     * Conflating them would mean a manager who can end-date a leaver can also lock out a colleague
     * mid-argument. Both revoke every session immediately, so the mechanism is identical and the
     * authority is not.
     */
    public static final String STAFF_OFFBOARD = "staff:offboard";

    // ── Account administration, scoped by WHOSE account it is. Session 65. ──────────────────────
    /*
     * Three permissions rather than one, because "can edit an account" is the wrong question. The
     * question is "can edit WHOSE account", and the answer differs by who is asking:
     *
     *   support agent  → customers only
     *   ops admin      → customers AND support staff
     *   super admin    → anyone, including other admins
     *
     * A single ACCOUNT_MANAGE permission could not express that, and the alternative — checking
     * the caller's role inline at every endpoint — puts the rule in six places and lets them drift.
     * AccountScope does the comparing; these three name the tiers.
     */

    /** Change a CUSTOMER's phone/email, block them, or remove them. */
    public static final String ACCOUNT_MANAGE_CUSTOMER = "account:manage_customer";

    /**
     * The same, for SALON-side people (owner, manager, stylist) and support staff.
     *
     * Separate from the customer tier because the blast radius is different: blocking a customer
     * stops one person booking, blocking a salon owner takes a business off the platform and
     * strands every appointment already in its diary.
     */
    public static final String ACCOUNT_MANAGE_STAFF = "account:manage_staff";

    /**
     * Any account at all, including other admins.
     *
     * Owner-only, and deliberately not granted to ops. An ops admin who can edit a super admin's
     * account can grant themselves anything — the ladder has to stop somewhere, and it stops here.
     */
    public static final String ACCOUNT_MANAGE_ANY = "account:manage_any";

    /**
     * SUPERADMIN is deliberately absent from this map — it's handled as a wildcard in
     * {@link #has}. Enumerating every permission for it would mean a new permission is silently
     * NOT granted to superadmin until someone remembers to add it, which is the wrong failure.
     */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.ofEntries(
            /*
             * Session 65 — ADMIN. Ops's permissions plus the ability to act on ops accounts.
             *
             * ACCOUNT_MANAGE_ANY is what makes that possible: AccountScope's admin branch needs it
             * to touch an ops_admin row. It does NOT make an admin equal to the owner — the
             * hierarchy check in RoleHierarchy runs first and refuses anything at or above the
             * actor's own rank, so an admin holding manage_any still cannot act on a super_admin
             * or on another admin.
             *
             * A permission says WHAT you can do; the rank says TO WHOM. Both are required.
             */
            Map.entry(ADMIN, Set.of(
                    SALON_REVIEW, SALON_VIEW, USER_VIEW, USER_PII_REVEAL, USER_DEACTIVATE,
                    BOOKING_VIEW, TICKET_VIEW, TICKET_WORK, TICKET_ESCALATE, TICKET_QUEUE_MANAGE,
                    CONTENT_MODERATE, DATA_REQUEST_VIEW, DATA_REQUEST_FULFIL, SETTINGS_MANAGE,
                    AUDIT_VIEW, REFUND_ISSUE,
                    // Session 65 — the rota and hiring, not account control. See TEAM_EDIT.
                    TEAM_EDIT, STAFF_HIRE, STAFF_OFFBOARD,
                    ACCOUNT_MANAGE_CUSTOMER, ACCOUNT_MANAGE_STAFF, ACCOUNT_MANAGE_ANY)),

            Map.entry(OPS_ADMIN, Set.of(
                    SALON_REVIEW, SALON_VIEW, USER_VIEW, USER_PII_REVEAL, USER_DEACTIVATE,
                    BOOKING_VIEW, TICKET_VIEW, TICKET_WORK, TICKET_ESCALATE, TICKET_QUEUE_MANAGE,
                    CONTENT_MODERATE, DATA_REQUEST_VIEW, DATA_REQUEST_FULFIL, SETTINGS_MANAGE,
                    AUDIT_VIEW,
                    // Session 65 — the rota, not the account. See TEAM_EDIT.
                    TEAM_EDIT, STAFF_HIRE, STAFF_OFFBOARD,
                    /*
                     * Session 65 — customers AND salon-side/support accounts. Not ACCOUNT_MANAGE_ANY:
                     * an ops admin who can edit a super admin's account can promote themselves, and
                     * every privilege ladder needs a rung it cannot reach.
                     */
                    ACCOUNT_MANAGE_CUSTOMER, ACCOUNT_MANAGE_STAFF)),

            Map.entry(SUPPORT_AGENT, Set.of(
                    SALON_VIEW, USER_VIEW, USER_PII_REVEAL, BOOKING_VIEW,
                    TICKET_VIEW, TICKET_WORK, TICKET_ESCALATE, CONTENT_MODERATE, DATA_REQUEST_VIEW,
                    // Session 65 — a customer on the phone saying "my number changed" is the
                    // commonest support request there is, and routing it to an admin made a
                    // 30-second fix into a two-day wait.
                    ACCOUNT_MANAGE_CUSTOMER)),

            /*
             * Session 57 — L2. An agent's powers, plus the team's queue.
             *
             * NOT given DATA_REQUEST_FULFIL (irreversible erasure) or SETTINGS_MANAGE (feature
             * flags, kill switch). Being senior in support does not make somebody senior over the
             * platform, and conflating the two is how a queue-management promotion quietly grants
             * the ability to turn bookings off.
             */
            Map.entry(SUPPORT_LEAD, Set.of(
                    SALON_VIEW, USER_VIEW, USER_PII_REVEAL, BOOKING_VIEW,
                    TICKET_VIEW, TICKET_WORK, TICKET_ESCALATE, TICKET_QUEUE_MANAGE,
                    CONTENT_MODERATE, DATA_REQUEST_VIEW, AUDIT_VIEW,
                    /*
                     * Session 65 — Darshan: "ops admin or support manager ... edit support member
                     * details". A lead maintains their own agents' job titles, shift notes and
                     * reporting lines. Rank stops them at their own rung, and TEAM_EDIT is
                     * deliberately not ACCOUNT_MANAGE_STAFF: running a rota is not the same as
                     * being able to suspend somebody or reissue their credentials.
                     */
                    TEAM_EDIT,
                    /*
                     * Session 65, Darshan's call: a support manager hires onto their own desk and
                     * end-dates a leaver, and does NOT suspend or reissue credentials. Those two
                     * are incident decisions and stay at ops.
                     *
                     * Notably absent: ACCOUNT_MANAGE_STAFF. A manager who held it could edit a
                     * SALON OWNER's account through AccountScope — a different table, a different
                     * blast radius, and nothing to do with running a desk.
                     */
                    STAFF_HIRE, STAFF_OFFBOARD,
                    // A lead's customer powers match an agent's. Seniority in support means
                    // managing the QUEUE, not reaching further up the account hierarchy — the same
                    // distinction that keeps DATA_REQUEST_FULFIL and SETTINGS_MANAGE away.
                    ACCOUNT_MANAGE_CUSTOMER)),

            /*
             * Finance gets NO account powers. Session 65.
             *
             * They can see an account to reconcile a payment and cannot change or block it.
             * Whoever moves the money should not also be able to alter the identity the money is
             * attached to — that is the oldest separation of duties there is.
             */
            Map.entry(FINANCE_ADMIN, Set.of(
                    SALON_VIEW, USER_VIEW, BOOKING_VIEW, REFUND_ISSUE, TICKET_VIEW, AUDIT_VIEW)),

            // For analysts and new joiners. Note it does NOT include USER_PII_REVEAL: read-only
            // means read the platform, not read customers' phone numbers.
            Map.entry(READ_ONLY, Set.of(SALON_VIEW, USER_VIEW, BOOKING_VIEW, TICKET_VIEW))
    );

    public static boolean has(String role, String permission) {
        if (role == null) return false;
        if (SUPER_ADMIN.equalsIgnoreCase(role)) return true;
        return ROLE_PERMISSIONS.getOrDefault(role.toLowerCase(), Set.of()).contains(permission);
    }

    /**
     * Everything a role can do — sent to the console so the UI hides what it cannot use.
     *
     * <h2>Super admin's set is DERIVED, never listed</h2>
     * It used to be a hand-written enumeration, and by Session 65 it had already drifted: three
     * account-administration permissions existed, {@link #has} granted them to the owner as a
     * wildcard, and this method did not return them — so the endpoints allowed the action while
     * the console hid the button. A permission that works but is invisible is indistinguishable
     * from one that is broken.
     *
     * <p>Reading the constants off this class instead means the two can never disagree: a
     * permission declared below is returned here the moment it is declared, matching the wildcard
     * in {@link #has}. That is the same reasoning the map's own javadoc gives for leaving super
     * admin OUT of ROLE_PERMISSIONS — this method just failed to follow it.
     *
     * <p>The filter is on the VALUE containing a colon, because every permission is
     * {@code area:action} and the only other String constant on this class
     * ({@link #UI_IS_NOT_THE_BOUNDARY}, prose) is not. Role names are excluded the same way —
     * "ops_admin" has no colon either.
     */
    public static Set<String> permissionsFor(String role) {
        if (SUPER_ADMIN.equalsIgnoreCase(role)) {
            return ALL_PERMISSIONS;
        }
        return ROLE_PERMISSIONS.getOrDefault(role == null ? "" : role.toLowerCase(), Set.of());
    }

    /** Computed once at class-load. See {@link #permissionsFor}. */
    private static final Set<String> ALL_PERMISSIONS = discoverAllPermissions();

    private static Set<String> discoverAllPermissions() {
        Set<String> found = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : StaffPermission.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(m) || !java.lang.reflect.Modifier.isStatic(m)
                    || f.getType() != String.class) {
                continue;
            }
            try {
                Object v = f.get(null);
                // area:action — role names and the prose constant have no colon.
                if (v instanceof String s && s.indexOf(':') > 0) {
                    found.add(s);
                }
            } catch (IllegalAccessException ignored) {
                // A public static field on this same class cannot be inaccessible. If the JVM ever
                // says otherwise, dropping that one permission is safer than failing class-load and
                // taking the whole console down.
            }
        }
        return Set.copyOf(found);
    }

    /**
     * The UI hiding a button is a courtesy, not a control — every endpoint checks server-side
     * too. This constant exists to make that expectation explicit to anyone reading the console
     * code and assuming the frontend is the gate.
     */
    public static final String UI_IS_NOT_THE_BOUNDARY =
            "Permissions sent to the client drive what is SHOWN. Authorization is enforced server-side, per endpoint.";
}
