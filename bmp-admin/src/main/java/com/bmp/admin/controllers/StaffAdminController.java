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
 * Staff management — creating and controlling console employee accounts.
 *
 * <h2>Session 65: ops admins run the desk, the owner runs the admins</h2>
 * This was superadmin-only. It is now superadmin AND ops admin, because a platform where only one
 * person can suspend a departing support agent is a platform where that agent keeps their access
 * until that person is free — and "wait for the founder" is not an offboarding procedure.
 *
 * <p>What has NOT changed is the ceiling. An ops admin may create, suspend and re-credential the
 * desk (support agents and leads, finance, read-only) and may do none of those to another ops admin
 * or to the owner. Creating accounts is the power that grants every other power: an ops admin who
 * could mint a superadmin could log in as one, and the hierarchy would be decorative.
 *
 * <h2>The annotation cannot express the real rule</h2>
 * {@code @PreAuthorize} here only proves the caller is one of the two roles that may be on this
 * screen at all. It CANNOT answer the actual question — "may this caller act on THIS account?" —
 * because that depends on the target's role, which is unknown until the row is loaded. So the
 * decision lives in {@code StaffAccountScope}, called by {@link StaffAdminService} after the load.
 * Widening the annotation without that service check would have handed ops the whole screen.
 */
@Tag(name = "Staff management", description = "Ops admin and the platform owner. Ops manages the support desk; only the owner can touch admin accounts. Nobody ever sets another person's password.")
@RestController
@RequestMapping("/api/v1/admin/staff")
/*
 * Session 65 — SUPPORT_LEAD added, and the annotation is doing even less than before.
 *
 * A support manager may HIRE onto their own desk and END-DATE a leaver, and may not suspend,
 * restore or re-credential anybody. None of that is expressible here: this annotation cannot see
 * the target's role, and after Session 65 it cannot see the requested STATUS either. It proves
 * only that the caller is senior enough to be on this screen at all.
 *
 * The three real rules live in StaffAccountScope — requireCanCreate, requireCanSetStatus,
 * requireCanManage — and are called by StaffAdminService after the row is loaded. Widening this
 * annotation without those would have handed the desk the whole screen.
 */
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPS_ADMIN','SUPPORT_LEAD')")
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
        summary = "Correct a staff member's name, phone or work email",
        description = """
            Fixes the three columns that were previously write-once. A typo in somebody's email             used to mean deleting the account and starting again — and since the email is how             they sign in, the typo was usually discovered when they could not.

            TWO AUTHORITIES ON ONE FORM. Name and phone need `team:edit`, so a support manager             can correct their own agents. The EMAIL needs `account:manage_staff` and is ops and             above, because it is the sign-in address rather than a contact detail — changing it             decides who can get into the account.

            Changing the email ENDS EVERY SESSION for that account. It is not a credential             reset: they sign in again at the new address with the same password. Reissue is the             separate, stronger action below.

            Omitted fields are left alone. If you may change only some of what you sent, the             whole request is refused rather than half-applied.""")
    @PutMapping("/{staffId}/identity")
    public StaffProfile updateIdentity(
            @PathVariable UUID staffId,
            @Valid @RequestBody StaffIdentityRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return staffAdmin.updateIdentity(staffId, req, caller, clientIp(http));
    }

    @Operation(
        summary = "Change somebody's role — promote or demote",
        description = """
            The powers half of "edit profiles of admins and their powers". A role used to be             write-once, so promoting a support agent meant deleting the account and making a new             one — losing their leave history, audit trail and email login. In practice nobody was             ever promoted.

            You must outrank BOTH their current role AND the new one. The second check is the one             that matters: without it an admin could promote somebody to main admin and then sign             in as them, which is a privilege escalation dressed as an HR action.

            EVERY SESSION FOR THAT PERSON ENDS. Permissions live in the access token, so a             demotion that left their token alive would leave the removed powers working for up to             fifteen minutes — and a demotion is usually the moment somebody's judgement is in             question.

            Their support tier moves with the role, and a reason is required.""")
    @PutMapping("/{staffId}/role")
    public StaffProfile changeRole(
            @PathVariable UUID staffId,
            @Valid @RequestBody StaffRoleChangeRequest req,
            @AuthenticationPrincipal StaffPrincipal caller,
            HttpServletRequest http) {
        return staffAdmin.changeRole(staffId, req.role(), req.reason(), caller, clientIp(http));
    }

    @Operation(
        summary = "What every role can do",
        description = """
            The permission set behind each role, and whether YOU can assign it.

            Read-only, and that is the design rather than a missing feature. BMP grants permissions             per ROLE, never per person: a per-user matrix sounds flexible and becomes impossible to             reason about — after a year nobody can answer "who can issue refunds?" without a             database query, and access reviews quietly stop happening. Seven roles a human can hold             in their head is worth more. When a role genuinely does not fit, the answer is a new             role, decided deliberately, in code, with a migration.

            Every rung is returned including ones above you, so the picker can SHOW what a main             admin is without letting you create one.""")
    @GetMapping("/roles")
    public List<RolePowers> rolePowers(@AuthenticationPrincipal StaffPrincipal caller) {
        return staffAdmin.rolePowers(caller);
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
