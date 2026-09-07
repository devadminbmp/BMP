package com.bmp.admin.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the permission and hierarchy tables against the failure mode that took the console down
 * in Session 65 — and that no amount of static analysis catches.
 *
 * <h2>What happened</h2>
 * A duplicate entry was left in {@code Set.of(...)} inside {@code ROLE_PERMISSIONS}:
 *
 * <pre>
 *   Map.entry(ADMIN, Set.of(..., TEAM_EDIT, REFUND_ISSUE,
 *                           ..., TEAM_EDIT, STAFF_HIRE, ...))
 * </pre>
 *
 * {@code Set.of} rejects duplicates by THROWING, and it throws from a static initialiser. So:
 *
 * <ul>
 *   <li>it compiles — the language has nothing to object to;</li>
 *   <li>a parse sweep sees valid Java;</li>
 *   <li>the class fails to initialise at RUNTIME, and every later touch of it reports
 *       {@code NoClassDefFoundError: Could not initialize class StaffPermission} — which names the
 *       symptom and hides the cause completely;</li>
 *   <li>and because {@code StaffPermission} is on the login path, <b>nobody can sign in at all</b>.</li>
 * </ul>
 *
 * <p>A single unlucky edit turned into a total outage of both consoles. These tests are cheap, run
 * in milliseconds with no Spring context, and would have caught it before it left the machine.
 *
 * <h2>Why the first test is "just load the class"</h2>
 * That is the entire bug. Referencing any member forces static initialisation, so this test fails
 * loudly with the REAL exception — {@code IllegalArgumentException: duplicate element} — instead of
 * the {@code NoClassDefFoundError} the running application reports.
 */
@DisplayName("Security constant tables")
class SecurityConstantsTest {

    @Test
    @DisplayName("every security class initialises — no duplicate Set.of/Map.of entries")
    void classesInitialise() {
        /*
         * Touch a member of each. If a static initialiser throws, the failure surfaces HERE with
         * the underlying cause attached, rather than as a NoClassDefFoundError at a login screen.
         */
        assertDoesNotThrow(() -> {
            assertNotNull(StaffPermission.SUPER_ADMIN);
            assertNotNull(StaffPermission.permissionsFor(StaffPermission.SUPER_ADMIN));
            assertTrue(RoleHierarchy.rankOf(RoleHierarchy.ADMIN) > 0);
            assertEquals(0, SupportTier.forRole(RoleHierarchy.ADMIN));
        }, "A security constant table failed to initialise — look for a duplicate entry in a Set.of or Map.of.");
    }

    /** Every role on the ladder must resolve to a permission set and a rank. */
    @Test
    @DisplayName("every console role has permissions, a rank and a tier")
    void everyRoleResolves() {
        for (String role : List.of(StaffPermission.SUPER_ADMIN, RoleHierarchy.ADMIN,
                StaffPermission.OPS_ADMIN, StaffPermission.SUPPORT_LEAD,
                StaffPermission.SUPPORT_AGENT, StaffPermission.FINANCE_ADMIN,
                StaffPermission.READ_ONLY)) {
            assertFalse(StaffPermission.permissionsFor(role).isEmpty(), role + " has no permissions");
            assertTrue(RoleHierarchy.rankOf(role) >= 0, role + " is not ranked");
            assertTrue(RoleHierarchy.isStaffRole(role), role + " is not a known staff role");
        }
    }

    /**
     * The ladder must be MONOTONIC: each rung holds everything the rung below holds.
     *
     * <p>This caught a real regression in Session 65 — {@code team:edit} was added to admin and
     * support_lead and missed on ops_admin, which would have silently removed a power from the
     * middle of the hierarchy. A permission map is edited by hand, three entries apart, and the eye
     * does not check supersets.
     */
    @Test
    @DisplayName("each rung holds everything the rung below it holds")
    void ladderIsMonotonic() {
        List<String> ladder = List.of(StaffPermission.SUPER_ADMIN, RoleHierarchy.ADMIN,
                StaffPermission.OPS_ADMIN, StaffPermission.SUPPORT_LEAD, StaffPermission.SUPPORT_AGENT);
        for (int i = 0; i < ladder.size() - 1; i++) {
            String above = ladder.get(i), below = ladder.get(i + 1);
            Set<String> a = StaffPermission.permissionsFor(above);
            Set<String> b = StaffPermission.permissionsFor(below);
            assertTrue(a.containsAll(b),
                    above + " is missing permissions that " + below + " holds: " + minus(b, a));
        }
    }

    /** Strictly greater rank, never equal — a peer may not manage a peer. */
    @Test
    @DisplayName("nobody can manage their own rank")
    void noPeerManagement() {
        for (String role : List.of(StaffPermission.SUPER_ADMIN, RoleHierarchy.ADMIN,
                StaffPermission.OPS_ADMIN, StaffPermission.SUPPORT_LEAD,
                StaffPermission.SUPPORT_AGENT, StaffPermission.FINANCE_ADMIN,
                StaffPermission.READ_ONLY)) {
            assertFalse(RoleHierarchy.canManage(role, role), role + " can manage its own rank");
        }
    }

    /**
     * Rank 0 means OUTSIDE the management chain, not junior to it.
     *
     * <p>The Session 65 bug: a plain {@code actor > target} let a support manager (rank 20) manage
     * a finance admin (rank 0) — the role that approves refunds.
     */
    @Test
    @DisplayName("off-ladder roles are reachable only from ops admin upward")
    void offLadderRolesNeedOps() {
        for (String offLadder : List.of(StaffPermission.FINANCE_ADMIN, StaffPermission.READ_ONLY)) {
            assertFalse(RoleHierarchy.canManage(StaffPermission.SUPPORT_AGENT, offLadder));
            assertFalse(RoleHierarchy.canManage(StaffPermission.SUPPORT_LEAD, offLadder),
                    "a support manager must not be able to manage " + offLadder);
            assertTrue(RoleHierarchy.canManage(StaffPermission.OPS_ADMIN, offLadder));
            assertTrue(RoleHierarchy.canManage(RoleHierarchy.ADMIN, offLadder));
        }
    }

    /** An unknown role manages nobody and is managed by anyone on the ladder. Fails closed. */
    @Test
    @DisplayName("an unrecognised role has no authority")
    void unknownRoleFailsClosed() {
        assertEquals(-1, RoleHierarchy.rankOf("director_of_vibes"));
        assertFalse(RoleHierarchy.canManage("director_of_vibes", StaffPermission.SUPPORT_AGENT));
        assertTrue(StaffPermission.permissionsFor("director_of_vibes").isEmpty());
        assertEquals(SupportTier.NONE, SupportTier.forRole("director_of_vibes"));
    }

    /**
     * ADMIN is managerial: it outranks ops and works no tickets.
     *
     * <p>Rank and tier are different ladders, and this is the pair that proves it — the one place
     * where a reader's instinct ("higher role, higher tier") is wrong on purpose.
     */
    @Test
    @DisplayName("ADMIN outranks ops and takes no tickets")
    void adminIsManagerial() {
        assertTrue(RoleHierarchy.rankOf(RoleHierarchy.ADMIN)
                 > RoleHierarchy.rankOf(StaffPermission.OPS_ADMIN));
        assertEquals(SupportTier.NONE, SupportTier.forRole(RoleHierarchy.ADMIN));
        assertTrue(SupportTier.forRole(StaffPermission.OPS_ADMIN) > 0);
    }

    private static String minus(Set<String> a, Set<String> b) {
        return a.stream().filter(x -> !b.contains(x)).sorted().toList().toString();
    }
}
