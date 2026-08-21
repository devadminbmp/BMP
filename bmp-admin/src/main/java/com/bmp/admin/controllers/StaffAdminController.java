package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminAuthDtos.*;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.StaffAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Staff management — the master admin's screen for creating and controlling employee accounts.
 *
 * <p><b>Superadmin only</b>, enforced twice: {@code @PreAuthorize} here, and again inside
 * {@link StaffAdminService}. Creating accounts is the power that grants every other power — an
 * ops admin who could create accounts could create a superadmin, making the whole role
 * hierarchy decorative. A permission that consequential shouldn't rest on one annotation being
 * remembered on one method.
 */
@Tag(name = "Staff management", description = "Superadmin only. Create employee accounts, change their status, and re-issue credentials. The admin never sets anyone's password.")
@RestController
@RequestMapping("/api/v1/admin/staff")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class StaffAdminController {

    private final StaffAdminService staffAdmin;

    public StaffAdminController(StaffAdminService staffAdmin) {
        this.staffAdmin = staffAdmin;
    }

    @Operation(
        summary = "Create an employee account",
        description = """
            Creates the account and returns a ONE-TIME activation code. Note there is no \
            password field: the employee sets their own, which nobody else ever sees.

            That's deliberate. An admin who knows a colleague's password makes every action \
            that colleague takes deniable — "someone else could have logged in as me" becomes \
            true — which destroys the audit log's value as evidence precisely when you need it.

            The code is shown once and stored only as a hash, so it cannot be retrieved again. \
            It works once and expires in 48 hours. The account is created 'invited' and cannot \
            be signed into until it's redeemed.""")
    @PostMapping
    public ResponseEntity<EmployeeCreatedResponse> createEmployee(
            @Valid @RequestBody CreateEmployeeRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(staffAdmin.createEmployee(req, caller, clientIp(http)));
    }

    @Operation(summary = "List all staff", description = "Newest first. The staff list is itself sensitive — it maps your organisation — so it's superadmin only.")
    @GetMapping
    public List<StaffProfile> listStaff(@AuthenticationPrincipal StaffPrincipal caller) {
        return staffAdmin.listStaff(caller);
    }

    @Operation(
        summary = "Suspend, restore or offboard",
        description = "Anything other than 'active' revokes every session for that person immediately — waiting for a token to expire is not good enough on the afternoon you let someone go. Refuses to change your own status, and refuses to deactivate the last active superadmin (unrecoverable without database access).")
    @PutMapping("/{staffId}/status")
    public StaffProfile changeStatus(
            @PathVariable UUID staffId,
            @Valid @RequestBody StaffStatusChangeRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return staffAdmin.changeStatus(staffId, req, caller, clientIp(http));
    }

    @Operation(
        summary = "Re-issue credentials (the lockout recovery path)",
        description = """
            Clears the password AND the two-factor secret, ends every session, and returns a \
            fresh one-time code.

            This is the ONLY reset mechanism — there is deliberately no self-service "forgot \
            password" email, because a reset link landing in a compromised inbox defeats \
            two-factor entirely. A human who knows the person hands them a new code. Verify \
            who you are speaking to before using this.""")
    @PostMapping("/{staffId}/reissue")
    public EmployeeCreatedResponse reissue(
            @PathVariable UUID staffId,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return staffAdmin.reissueActivation(staffId, caller, clientIp(http));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
