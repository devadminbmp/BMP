package com.bmp.admin.services;

import com.bmp.admin.dto.AdminAuthDtos.*;
import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.StaffSessionRepository;
import com.bmp.admin.security.StaffBootstrap;
import com.bmp.admin.security.RoleHierarchy;
import com.bmp.admin.security.SupportTier;
import com.bmp.admin.security.StaffAccountScope;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff management — the master admin creating and controlling employee accounts.
 *
 * <h2>Who can use any of this: superadmin only</h2>
 * Creating accounts is the power that grants every other power. An ops admin who could create
 * accounts could create a superadmin, which makes the whole role hierarchy decorative. So
 * {@code staff:manage} belongs to superadmin alone, and this service re-checks rather than
 * trusting the controller annotation.
 *
 * <h2>The master admin never sets a password</h2>
 * They create the ACCOUNT and receive a one-time activation code to pass on. The employee sets
 * a password nobody else has ever seen. See V004's header — an admin who knows a colleague's
 * password makes every action that colleague takes deniable, which destroys the audit log's
 * value as evidence exactly when you need it.
 */
@Service
public class StaffAdminService {

    private static final Logger log = LoggerFactory.getLogger(StaffAdminService.class);

    private final BmpStaffRepository staffRepo;
    private final StaffSessionRepository sessionRepo;
    private final StaffAuthService authService;
    private final AuditLogService audit;

    public StaffAdminService(BmpStaffRepository staffRepo, StaffSessionRepository sessionRepo,
                             StaffAuthService authService, AuditLogService audit) {
        this.staffRepo = staffRepo;
        this.sessionRepo = sessionRepo;
        this.authService = authService;
        this.audit = audit;
    }

    /**
     * Create an employee.
     *
     * <p>Returns the activation code ONCE — only its hash is stored, so it can never be
     * retrieved again. The master admin passes it to the employee out of band, exactly like the
     * salon manager invites.
     */
    @Transactional
    public EmployeeCreatedResponse createEmployee(CreateEmployeeRequest req, StaffPrincipal caller, String ip) {
        /*
         * Session 65 — gated on the role BEING CREATED, not on the caller alone.
         *
         * An ops admin may hire the desk (agents, leads, finance, read-only) and may not mint
         * another admin. Checking the caller's own role would answer the wrong question and would
         * let an ops admin create a super admin and then log in as them.
         *
         * requireCanCreate, not requireCanManage: Session 65 splits HIRING from ACCOUNT CONTROL so
         * a support manager can staff their own desk without also being able to suspend or
         * re-credential people. Rank is unchanged — you may still only mint below yourself.
         */
        StaffAccountScope.requireCanCreate(caller, req.role());

        String email = req.email().trim().toLowerCase();
        if (staffRepo.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A staff account already exists for that email.");
        }
        if (staffRepo.existsByPhone(req.phone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A staff account already exists for that phone number.");
        }

        // Created LOCKED and 'invited': no password exists, and the login flow refuses anything
        // that isn't 'active'. So an account is unusable between creation and activation even
        // if someone guesses the email.
        BmpStaff staff = new BmpStaff(
                req.name().trim(), req.phone(), email,
                StaffBootstrap.LOCKED, req.role(), "invited", null);
        staff.setCreatedBy(caller.staffId());
        staff = staffRepo.save(staff);

        String code = authService.issueActivationCode(staff, "activation", caller.staffId());

        audit.record("bmp_staff", caller.staffId(), "STAFF_CREATED", "bmp_staff", staff.getId(),
                Map.of("email", email, "role", req.role()), ip, caller.email(), caller.role(), null);
        log.info("Staff account created: {} ({}) by {}", email, req.role(), caller.email());

        return new EmployeeCreatedResponse(
                authService.toProfile(staff),
                code,
                Instant.now().plus(48, ChronoUnit.HOURS),
                "Send this code to " + req.name() + ". They open the console, choose "
                + "\"I have an activation code\", set their own password and then set up "
                + "two-factor. The code works once and expires in 48 hours. "
                + "You will not be able to see it again.");
    }

    /**
     * Everyone, newest first.
     *
     * <p>Session 65: ops admins see it too, because you cannot manage the desk without seeing who
     * is on it. The list is still sensitive — it is every console account there is — so it stops
     * at ops; support agents and finance have no reason to enumerate their colleagues.
     */
    public List<StaffProfile> listStaff(StaffPrincipal caller) {
        if (!StaffAccountScope.canView(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a support manager or above can see staff accounts.");
        }
        /*
         * Session 65 — FILTERED BY RANK, not just gated.
         *
         * A support manager can now reach this list (they hire onto their own desk and need to see
         * what they hired), and must not be able to enumerate ops admins, finance or the owner.
         * Same comparison the action guards use, so the rows shown are exactly the rows whose
         * buttons work — plus your own, which is always visible.
         */
        return staffRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(s -> StaffAccountScope.visibleTo(caller, s.getRole(), s.getId()))
                .map(authService::toProfile)
                .toList();
    }

    /**
     * Suspend, restore or offboard.
     *
     * <p>Suspending and offboarding REVOKE EVERY SESSION immediately. Without that, removing
     * someone's access means waiting for their token to expire — which on the afternoon you
     * let someone go is not good enough.
     */
    @Transactional
    public StaffProfile changeStatus(UUID staffId, StaffStatusChangeRequest req, StaffPrincipal caller, String ip) {
        BmpStaff staff = staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        /*
         * Session 65 — loaded FIRST, then authorised against the target's role.
         *
         * The order matters and it is not the usual one. "Can this caller act?" is unanswerable
         * until we know WHOSE account this is: an ops admin may suspend an agent and may not
         * suspend another ops admin, and the two requests look identical until the row is read.
         *
         * The self-check that used to live here now lives in StaffAccountScope, so it applies to
         * every staff operation rather than only to this one.
         */
        /*
         * Session 65 — status-aware. "Offboard a leaver" and "suspend somebody right now" write the
         * same column and are different authorities; a support manager holds the first and not the
         * second. See StaffAccountScope.requireCanSetStatus.
         */
        StaffAccountScope.requireCanSetStatus(caller, staff.getRole(), staff.getId(), req.status());
        if (StaffPermission.SUPER_ADMIN.equalsIgnoreCase(staff.getRole()) && !"active".equals(req.status())) {
            long activeSuperAdmins = staffRepo.findByRole(StaffPermission.SUPER_ADMIN).stream()
                    .filter(BmpStaff::isActive)
                    .count();
            if (activeSuperAdmins <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This is the last active superadmin. Promote someone else first.");
            }
        }

        staff.setStatus(req.status());
        staff.touch();
        staffRepo.save(staff);

        if (!"active".equals(req.status())) {
            int revoked = sessionRepo.revokeAllForStaff(staff.getId());
            log.info("Revoked {} session(s) for {} on status change to {}", revoked, staff.getEmail(), req.status());
        }

        audit.record("bmp_staff", caller.staffId(), "STAFF_STATUS_CHANGED", "bmp_staff", staff.getId(),
                Map.of("status", req.status(), "reason", req.reason() == null ? "" : req.reason()),
                ip, caller.email(), caller.role(), req.reason());

        return authService.toProfile(staff);
    }

    /**
     * Correct a staff member's IDENTITY — name, phone, work email. Session 65.
     *
     * <p>Darshan: <i>"edit support member details"</i>, <i>"he edit or create profiles of admins"</i>.
     * Until now these three columns were write-once: set at creation and unreachable afterwards, so
     * a typo in somebody's email meant deleting the account and starting again — and their email is
     * how they sign in, so the typo was often only discovered when they could not.
     *
     * <h2>Two different authorities on one form, and why</h2>
     * <pre>
     *   name, phone   team:edit            a personnel-record correction. A support manager may.
     *   EMAIL         account:manage_staff THE LOGIN. Ops and above only.
     * </pre>
     *
     * <p>The email is not a contact detail here — it is the username on the sign-in screen and the
     * address an activation code is sent to. Changing it decides who can get into the account, so it
     * belongs with suspend and reissue rather than with job title and shift note. This is the same
     * split as {@code STAFF_HIRE} vs {@code ACCOUNT_MANAGE_STAFF}: a manager runs their desk, and
     * credentials stay with ops.
     *
     * <p>Refusing the whole request when the caller may change only some of the fields, rather than
     * silently applying the permitted half — a partial save that reports success is how somebody
     * believes an email was corrected when it was not.
     */
    @Transactional
    public StaffProfile updateIdentity(UUID staffId, StaffIdentityRequest req,
                                        StaffPrincipal caller, String ip) {
        BmpStaff staff = staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        // Rank first, and against the TARGET's role — the same order as every other staff write.
        StaffAccountScope.requireCanEditEmployment(caller, staff.getRole(), staff.getId());

        boolean changingEmail = req.email() != null
                && !req.email().trim().equalsIgnoreCase(staff.getEmail() == null ? "" : staff.getEmail());

        if (changingEmail && !(caller.can(StaffPermission.ACCOUNT_MANAGE_STAFF)
                            || caller.can(StaffPermission.STAFF_MANAGE))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A work email is the sign-in address, so changing it is an ops decision. "
                    + "You can correct the name and phone number.");
        }

        java.util.Map<String, Object> changed = new java.util.LinkedHashMap<>();

        if (req.name() != null && !req.name().isBlank() && !req.name().equals(staff.getName())) {
            changed.put("name", staff.getName() + " → " + req.name().trim());
            staff.setName(req.name().trim());
        }

        if (req.phone() != null && !req.phone().equals(staff.getPhone())) {
            /*
             * Uniqueness checked BEFORE writing, so the caller gets 409 CONFLICT with a sentence
             * rather than a 500 from the unique index. The index is still what guarantees it —
             * this check is for the message, not for the rule (a check-then-act cannot be the
             * guarantee, and Session 65's V027 is the standing example).
             */
            if (staffRepo.existsByPhone(req.phone())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another staff account already uses that phone number.");
            }
            changed.put("phone", "changed");   // never the number itself, in a log anyone can read
            staff.setPhone(req.phone());
        }

        if (changingEmail) {
            String email = req.email().trim().toLowerCase();
            if (staffRepo.existsByEmailIgnoreCase(email)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another staff account already uses that email.");
            }
            changed.put("email", staff.getEmail() + " → " + email);
            staff.setEmail(email);

            /*
             * EVERY SESSION ENDS when the sign-in address changes.
             *
             * Two reasons, and the second is the one that matters. A live session belongs to
             * whoever authenticated as the OLD address, and if this change is happening because
             * an account was compromised, leaving that session alive defeats the entire point of
             * making the change. Same reasoning as suspension.
             *
             * They sign in again with the new address and their existing password — this is not a
             * credential reset. Reissue is the separate, stronger action.
             */
            int revoked = sessionRepo.revokeAllForStaff(staff.getId());
            log.info("Staff email changed for {} — revoked {} session(s)", staffId, revoked);
        }

        if (changed.isEmpty()) {
            // Nothing to do, and nothing to audit. Returning the profile unchanged is honest;
            // writing an audit entry saying "updated" when nothing moved is noise that later
            // reads as evidence of something happening.
            return authService.toProfile(staff);
        }

        staff.touch();
        staffRepo.save(staff);

        audit.record("bmp_staff", caller.staffId(), "STAFF_IDENTITY_UPDATED", "bmp_staff", staffId,
                changed, ip, caller.email(), caller.role(), req.reason());
        log.info("Staff identity updated for {} by {}: {}", staffId, caller.email(), changed.keySet());

        return authService.toProfile(staff);
    }

    /**
     * Every role, what it grants, and whether this caller may hand it out. Session 65.
     *
     * <p>Ordered highest-authority-first so the list reads like the org chart rather than like
     * whatever order a hash map produced.
     *
     * <p>Returns rungs ABOVE the caller too, with {@code canAssign = false}. Hiding them would
     * leave somebody wondering whether a main admin role exists at all; showing it greyed out
     * answers the question and names the boundary in the same glance.
     */
    @Transactional(readOnly = true)
    public java.util.List<RolePowers> rolePowers(StaffPrincipal caller) {
        return java.util.stream.Stream.of(
                        StaffPermission.SUPER_ADMIN, RoleHierarchy.ADMIN, StaffPermission.OPS_ADMIN,
                        StaffPermission.SUPPORT_LEAD, StaffPermission.SUPPORT_AGENT,
                        StaffPermission.FINANCE_ADMIN, StaffPermission.READ_ONLY)
                .map(r -> new RolePowers(
                        r,
                        RoleHierarchy.label(r),
                        RoleHierarchy.rankOf(r),
                        SupportTier.forRole(r),
                        // Sorted so two roles can be compared by eye without hunting.
                        StaffPermission.permissionsFor(r).stream().sorted().toList(),
                        caller != null && RoleHierarchy.canManage(caller.role(), r)))
                .toList();
    }

    /**
     * PROMOTE OR DEMOTE somebody. Session 65.
     *
     * <p>Darshan: <i>"he edit or create profiles of admins and their powers"</i>. The powers half
     * was missing entirely — a staff member's role was WRITE-ONCE. Promoting a support agent to
     * support manager meant deleting the account and creating a new one, which loses their leave
     * history, their audit trail and their ticket assignments, and hands them a new email login.
     * In practice it meant nobody was ever promoted.
     *
     * <h2>You must outrank BOTH roles, and that is two checks, not one</h2>
     * <pre>
     *   requireCanManage(caller, CURRENT role)  — may you act on this person at all?
     *   requireCanManage(caller, NEW role)      — may you hand out that much authority?
     * </pre>
     *
     * The second is the one that is easy to forget and the one that matters. Without it an admin
     * (rank 40) could promote a support agent straight to super_admin (rank 50) and then log in as
     * them — a privilege escalation dressed as an HR action. Checking only the current role asks
     * "can I touch this person", which is not the question a promotion poses.
     *
     * <h2>EVERY SESSION ENDS</h2>
     * Permissions are baked into the access token at sign-in. A demotion that leaves their token
     * alive means they keep the powers you just removed until it expires — and a demotion is
     * usually the moment somebody's judgement is in question, which is exactly when a fifteen
     * minute grace period is unacceptable. Revoking also fixes the reverse: a promotion takes
     * effect on their next sign-in rather than silently not working.
     *
     * <h2>The tier moves too</h2>
     * Role and support tier are separate ladders (see RoleHierarchy vs SupportTier) and the tier is
     * DERIVED from the role. Leaving it behind would put a promoted manager in the agent queue, or
     * a demoted admin nowhere at all.
     */
    @Transactional
    public StaffProfile changeRole(UUID staffId, String newRole, String reason,
                                    StaffPrincipal caller, String ip) {
        BmpStaff staff = staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        String from = staff.getRole();
        String to = newRole == null ? "" : newRole.trim().toLowerCase();

        if (to.equals(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "They already have that role.");
        }
        if (!RoleHierarchy.isStaffRole(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a console role: " + newRole);
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why — a change of authority is the kind of entry somebody reads back later.");
        }

        // 1. May you act on this person? (Also does the self-check — you cannot promote yourself.)
        StaffAccountScope.requireCanManage(caller, from, staffId);
        // 2. May you GRANT that much? The check that stops a promotion being an escalation.
        if (!RoleHierarchy.canManage(caller.role(), to)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can't give somebody a role at or above your own. "
                    + RoleHierarchy.explain(to));
        }

        /*
         * Same last-owner guard as changeStatus. Demoting the only active super_admin locks
         * everybody out of everything that is owner-only, including the ability to undo it.
         */
        if (StaffPermission.SUPER_ADMIN.equalsIgnoreCase(from)) {
            long activeOwners = staffRepo.findByRole(StaffPermission.SUPER_ADMIN).stream()
                    .filter(BmpStaff::isActive).count();
            if (activeOwners <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This is the last active main admin. Promote somebody else first.");
            }
        }

        staff.setRole(to);
        staff.setTier(SupportTier.forRole(to));
        /*
         * Off the ladder means out of the queue. A promoted admin (tier 0) left with
         * accepting_tickets = true would show as available on the Team screen while receiving
         * nothing — the same misleading state V015 cleaned up.
         */
        if (staff.getTier() == SupportTier.NONE) {
            staff.setAcceptingTickets(false);
        }
        staff.touch();
        staffRepo.save(staff);

        int revoked = sessionRepo.revokeAllForStaff(staffId);

        audit.record("bmp_staff", caller.staffId(), "STAFF_ROLE_CHANGED", "bmp_staff", staffId,
                Map.of("who", String.valueOf(staff.getEmail()), "from", from, "to", to,
                       "sessionsRevoked", String.valueOf(revoked)),
                ip, caller.email(), caller.role(), reason);
        log.warn("ROLE CHANGE: {} {} → {} by {} — {}", staff.getEmail(), from, to, caller.email(), reason);

        return authService.toProfile(staff);
    }

    /**
     * Re-issue an activation code — the recovery path for someone locked out.
     *
     * <p>This is deliberately the ONLY reset mechanism. There is no self-service "forgot
     * password" email, because a reset link landing in a compromised inbox defeats two-factor
     * entirely. A human who knows the person hands them a new code.
     *
     * <p>It also clears their TOTP secret: someone who has lost their phone needs to re-enrol,
     * and that is the common reason for asking.
     */
    @Transactional
    public EmployeeCreatedResponse reissueActivation(UUID staffId, StaffPrincipal caller, String ip) {
        BmpStaff staff = staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        /*
         * Session 65 — the same scope as suspending them, and for a stronger reason.
         *
         * This clears the password AND the TOTP secret and hands back a fresh activation code. It
         * is not "help someone back in", it is "take over this account with one code" — so an ops
         * admin may do it for the desk and never for another admin.
         */
        StaffAccountScope.requireCanManage(caller, staff.getRole(), staff.getId());

        staff.setPasswordHash(StaffBootstrap.LOCKED);
        staff.setTotpSecret(null);
        staff.setTotpEnrolledAt(null);
        staff.setStatus("invited");
        staff.setFailedLoginCount(0);
        staff.setLockedUntil(null);
        staff.touch();
        staffRepo.save(staff);

        // Their existing sessions die with the reset — otherwise a stolen laptop stays signed
        // in through the very action meant to recover the account.
        sessionRepo.revokeAllForStaff(staff.getId());

        String code = authService.issueActivationCode(staff, "reset", caller.staffId());

        audit.record("bmp_staff", caller.staffId(), "STAFF_CREDENTIALS_RESET", "bmp_staff", staff.getId(),
                Map.of("email", staff.getEmail()), ip, caller.email(), caller.role(), null);
        log.warn("Credentials reset for {} by {}", staff.getEmail(), caller.email());

        return new EmployeeCreatedResponse(
                authService.toProfile(staff),
                code,
                Instant.now().plus(48, ChronoUnit.HOURS),
                "Their password and two-factor have been cleared and all sessions ended. "
                + "Give them this code to set both up again. Verify who you're speaking to first.");
    }

}
