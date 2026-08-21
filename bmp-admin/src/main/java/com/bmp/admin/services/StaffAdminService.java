package com.bmp.admin.services;

import com.bmp.admin.dto.AdminAuthDtos.*;
import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.StaffSessionRepository;
import com.bmp.admin.security.StaffBootstrap;
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
        requireSuperAdmin(caller);

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

    /** Everyone, newest first. Superadmin only — the staff list is itself sensitive. */
    public List<StaffProfile> listStaff(StaffPrincipal caller) {
        requireSuperAdmin(caller);
        return staffRepo.findAllByOrderByCreatedAtDesc().stream().map(authService::toProfile).toList();
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
        requireSuperAdmin(caller);

        BmpStaff staff = staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        // Locking yourself out of the only superadmin account is unrecoverable without database
        // access, and it is a genuinely easy mistake to make at speed.
        if (staff.getId().equals(caller.staffId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot change your own status.");
        }
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
        requireSuperAdmin(caller);

        BmpStaff staff = staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

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

    /**
     * Re-checked here, not merely annotated on the controller.
     *
     * <p>A permission this consequential shouldn't depend on one annotation being present on
     * one method — the day someone adds a new endpoint and forgets it, the service still says no.
     */
    private void requireSuperAdmin(StaffPrincipal caller) {
        if (caller == null || !StaffPermission.has(caller.role(), StaffPermission.STAFF_MANAGE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a superadmin can manage staff accounts.");
        }
    }
}
