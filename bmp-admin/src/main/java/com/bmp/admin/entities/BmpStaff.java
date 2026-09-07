package com.bmp.admin.entities;

import com.bmp.admin.security.SupportTier;
import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for admin_schema.bmp_staff.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "bmp_staff", schema = "admin_schema")
@Getter
public class BmpStaff {

    @Id
    private UUID id;

    @Setter
    @Column(name = "name", nullable = false, length = 120)
    private String name;
    @Setter
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Setter
    @Column(name = "email", length = 160)
    private String email;
    @Setter
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Setter
    @Column(name = "role", nullable = false, length = 20)
    private String role;
    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Setter
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // ---- Session 20: 2FA + lockout (see V003) ------------------------------------------

    /**
     * Base32 TOTP secret (RFC 6238). NULL until the staff member enrols.
     *
     * <p>Login is refused past the password step while this is null, so 2FA cannot be skipped —
     * not by the staff member, and not by whoever created the account. A password alone
     * protecting a console that reads every customer's personal data isn't defensible, and
     * password reuse is universal.
     */
    @Setter
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Setter
    @Column(name = "totp_enrolled_at")
    private Instant totpEnrolledAt;

    @Setter
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Setter
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Who created this account — the first question asked after an incident. */
    @Setter
    @Column(name = "created_by")
    private UUID createdBy;
    // ══ V009 (Session 57) — the support ladder and the queue ══════════════════════════════════

    /**
     * Where this person sits on the escalation ladder.
     *
     * <p>1 = agent, 2 = lead, 3 = ops, 4 = owner. <b>0 = not on the ladder at all</b> — finance
     * and read-only analysts, who may READ tickets and can never be assigned one. An analyst
     * holding a customer's complaint is not an analyst.
     *
     * <p>Stored rather than derived from {@code role} so escalation is one integer comparison. The
     * alternative puts the ladder in a switch statement inside whichever service escalates, and
     * there is eventually a second one that disagrees with the first.
     */
    @Setter
    @Column(name = "tier", nullable = false)
    private short tier;

    /**
     * Whether THIS ops admin may create staff accounts. Granted individually by a super_admin.
     *
     * <p>Darshan: <i>"ops admin can create support accounts for new users, but not all ops
     * admins."</i> A capability flag rather than an {@code ops_admin_senior} role — inventing a
     * role per extra power turns the permission matrix combinatorial, and this is how Zendesk and
     * Freshdesk model it too: a role, plus privileges granted to the individual.
     *
     * <p>Default false. Hiring is opt-in, never a side effect of being promoted to ops.
     */
    @Setter
    @Column(name = "can_manage_staff", nullable = false)
    private boolean canManageStaff;

    /**
     * The agent's own switch — on leave, in a meeting, shift over. New staff default to TRUE,
     * which is what "new joiners are automatically in the rotation" means in practice.
     */
    @Setter
    @Column(name = "accepting_tickets", nullable = false)
    private boolean acceptingTickets = true;

    /**
     * Open tickets currently held. Denormalised on purpose: choosing the least-loaded assignee is
     * on the hot path of every incoming ticket, and counting across support_ticket to answer it
     * would get slower exactly as the desk gets busier.
     *
     * <p>Maintained by {@code TicketAssignmentService} — incremented on assign, decremented on
     * resolve or hand-off. Drift is possible in principle; {@code recount()} exists for that.
     */
    @Column(name = "open_ticket_count", nullable = false)
    private int openTicketCount;
    // ══ V011 (Session 59) — the employee record ═══════════════════════════════════════════════

    /**
     * Added to THIS table rather than a parallel {@code employee} one.
     *
     * <p>A second table keyed to the same person is two rows that drift: somebody is deactivated in
     * one and not the other, and the team list and the login list stop agreeing about who works
     * here.
     *
     * <p><b>Deliberately absent: salary, bank details, government identifiers.</b> Those belong in
     * a payroll system with a different access model. Putting them one field away from a support
     * console — where five roles can already read staff rows — is how a console permission becomes
     * a payroll breach.
     */
    @Setter
    @Column(name = "employee_code", length = 20)
    private String employeeCode;

    @Setter
    @Column(name = "joined_on")
    private java.time.LocalDate joinedOn;

    /** The org chart. Null for the owner, who reports to nobody. */
    @Setter
    @Column(name = "reports_to_staff_id")
    private UUID reportsToStaffId;

    @Setter
    @Column(name = "job_title", length = 80)
    private String jobTitle;

    /** Free text — shifts vary, and a structured rota is a feature nobody has asked for. */
    @Setter
    @Column(name = "shift_note", length = 120)
    private String shiftNote;

    @Setter
    @Column(name = "exited_on")
    private java.time.LocalDate exitedOn;


    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BmpStaff() {} // JPA

    public BmpStaff(String name, String phone, String email, String passwordHash, String role, String status, Instant lastLoginAt) {
        this.id = UuidV7.generate();
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.lastLoginAt = lastLoginAt;

        /*
         * ── TIER COMES FROM THE ROLE, at construction. Session 65. ─────────────────────────────
         *
         * This line used to be absent, and the column's DEFAULT 1 (V009) filled the gap. That made
         * every console-created account a tier-1 support agent for assignment purposes — including
         * finance_admin and read_only, who are explicitly off the ladder, and now `admin`, which
         * Darshan confirmed is managerial and takes no tickets.
         *
         * findNextAssignee selects on `tier = ? AND tier > 0 AND accepting_tickets`, so the effect
         * was a finance admin quietly becoming eligible for customer tickets. V009's backfill fixed
         * the rows that existed at the time; nothing protected the ones created afterwards.
         *
         * Setting it here means the invariant holds for every row this code writes. V015 repairs
         * the ones already written wrong.
         */
        this.tier = SupportTier.forRole(role);
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }

    /** True once 2FA is set up. Until then the console must not let them past login. */
    public boolean isTotpEnrolled() {
        return totpSecret != null && !totpSecret.isBlank();
    }

    /** True while a brute-force lockout is in force. */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }

    /** Same conditions as {@code findNextAssignee}'s query — on the ladder, active, and taking work. */
    public boolean isAssignable() {
        return tier > 0 && isActive() && acceptingTickets;
    }

    /** Floors at zero — resolving an already-resolved ticket must not push the count negative. */
    public void adjustOpenTickets(int delta) {
        this.openTicketCount = Math.max(0, this.openTicketCount + delta);
    }
}
