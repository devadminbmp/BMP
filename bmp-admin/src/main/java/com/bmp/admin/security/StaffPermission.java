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
    public static final String OPS_ADMIN = "ops_admin";
    public static final String SUPPORT_AGENT = "support_agent";
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

    /**
     * SUPERADMIN is deliberately absent from this map — it's handled as a wildcard in
     * {@link #has}. Enumerating every permission for it would mean a new permission is silently
     * NOT granted to superadmin until someone remembers to add it, which is the wrong failure.
     */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            OPS_ADMIN, Set.of(
                    SALON_REVIEW, SALON_VIEW, USER_VIEW, USER_PII_REVEAL, USER_DEACTIVATE,
                    BOOKING_VIEW, TICKET_VIEW, TICKET_WORK, CONTENT_MODERATE,
                    DATA_REQUEST_VIEW, DATA_REQUEST_FULFIL, SETTINGS_MANAGE, AUDIT_VIEW),

            SUPPORT_AGENT, Set.of(
                    SALON_VIEW, USER_VIEW, USER_PII_REVEAL, BOOKING_VIEW,
                    TICKET_VIEW, TICKET_WORK, CONTENT_MODERATE, DATA_REQUEST_VIEW),

            FINANCE_ADMIN, Set.of(
                    SALON_VIEW, USER_VIEW, BOOKING_VIEW, REFUND_ISSUE, TICKET_VIEW, AUDIT_VIEW),

            // For analysts and new joiners. Note it does NOT include USER_PII_REVEAL: read-only
            // means read the platform, not read customers' phone numbers.
            READ_ONLY, Set.of(SALON_VIEW, USER_VIEW, BOOKING_VIEW, TICKET_VIEW)
    );

    public static boolean has(String role, String permission) {
        if (role == null) return false;
        if (SUPER_ADMIN.equalsIgnoreCase(role)) return true;
        return ROLE_PERMISSIONS.getOrDefault(role.toLowerCase(), Set.of()).contains(permission);
    }

    /** Everything a role can do — sent to the console so the UI hides what it cannot use. */
    public static Set<String> permissionsFor(String role) {
        if (SUPER_ADMIN.equalsIgnoreCase(role)) {
            return Set.of(SALON_REVIEW, SALON_VIEW, USER_VIEW, USER_PII_REVEAL, USER_DEACTIVATE,
                    BOOKING_VIEW, REFUND_ISSUE, TICKET_VIEW, TICKET_WORK, CONTENT_MODERATE,
                    DATA_REQUEST_VIEW, DATA_REQUEST_FULFIL, STAFF_MANAGE, SETTINGS_MANAGE, AUDIT_VIEW);
        }
        return ROLE_PERMISSIONS.getOrDefault(role == null ? "" : role.toLowerCase(), Set.of());
    }

    /**
     * The UI hiding a button is a courtesy, not a control — every endpoint checks server-side
     * too. This constant exists to make that expectation explicit to anyone reading the console
     * code and assuming the frontend is the gate.
     */
    public static final String UI_IS_NOT_THE_BOUNDARY =
            "Permissions sent to the client drive what is SHOWN. Authorization is enforced server-side, per endpoint.";
}
