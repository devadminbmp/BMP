package com.bmp.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Console authentication and staff-management shapes.
 *
 * <p>The login response is a small state machine rather than a boolean, because there are three
 * genuinely different outcomes and collapsing them is how half-authenticated sessions happen:
 * you passed the password and now need a code; you passed the password but have never set up
 * 2FA; or you're in.
 */
public final class AdminAuthDtos {
    private AdminAuthDtos() {}

    /**
     * Minimum password length.
     *
     * <p>16, not 8. Staff use a password manager, this account can read every customer's
     * personal data, and length beats character-class rules — which only ever produce
     * {@code Password1!} and a sticky note.
     */
    public static final int MIN_PASSWORD_LENGTH = 16;

    public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
    ) {}

    public record TotpRequest(
        @NotBlank String challengeToken,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "code must be 6 digits") String code
    ) {}

    /** Redeeming a one-time activation code: the employee sets their OWN password here. */
    public record ActivateRequest(
        @NotBlank String activationCode,
        @NotBlank @Size(min = MIN_PASSWORD_LENGTH, message = "password must be at least 16 characters")
        String newPassword
    ) {}

    /**
     * Rotating your own password. Session 65.
     *
     * <p>BOTH the current password and a live 2FA code. See StaffAuthService.changeOwnPassword for
     * why the code is not optional: without it, a leaked password is enough to take the account
     * permanently rather than for one session.
     */
    public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 6, max = 6, message = "Six digits, from your authenticator app.")
        String totpCode,
        @NotBlank @Size(min = MIN_PASSWORD_LENGTH, message = "password must be at least 16 characters")
        String newPassword
    ) {}

    /** Your own name and phone. Email, role and status are deliberately absent — see the service. */
    public record UpdateOwnProfileRequest(
        @Size(max = 120) String name,
        @Pattern(regexp = "^\\+91[6-9][0-9]{9}$|^$", message = "Use +91 followed by a ten-digit mobile number.")
        String phone
    ) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    /** What the console needs to render a signed-in staff member. */
    public record StaffProfile(
        UUID id, String name, String email, String role, String status,
        Set<String> permissions, boolean totpEnrolled, Instant lastLoginAt
    ) {}

    /**
     * The one response shape for every step of login.
     *
     * @param stage TOTP_REQUIRED | TOTP_ENROLMENT_REQUIRED | AUTHENTICATED
     * @param challengeToken short-lived proof the password step passed; null once authenticated
     * @param totpProvisioningUri only on enrolment — the otpauth:// URI to show as a QR code
     */
    public record LoginResponse(
        String stage,
        String challengeToken,
        String accessToken,
        String refreshToken,
        Long expiresIn,
        StaffProfile staff,
        String totpProvisioningUri
    ) {
        public static LoginResponse challenge(String token) {
            return new LoginResponse("TOTP_REQUIRED", token, null, null, null, null, null);
        }

        public static LoginResponse enrolment(String token, String provisioningUri) {
            return new LoginResponse("TOTP_ENROLMENT_REQUIRED", token, null, null, null, null, provisioningUri);
        }

        public static LoginResponse authenticated(String access, String refresh, long expiresIn, StaffProfile staff) {
            return new LoginResponse("AUTHENTICATED", null, access, refresh, expiresIn, staff, null);
        }
    }

    // ---- staff management (master admin) --------------------------------------------------

    /**
     * Create an employee account.
     *
     * <p>Note what's absent: a password field. The master admin creates the account and gets a
     * one-time activation code; the employee sets a password nobody else has ever seen. See
     * V004's header for why an admin-chosen password quietly destroys the audit trail's value.
     */
    public record CreateEmployeeRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +919876543210")
        String phone,
        /*
         * Session 65 — support_lead was MISSING from this pattern.
         *
         * The role has existed since Session 57, has its own permission set, sits in the seed and
         * runs the support desk — and could not be created through the API at all. Every attempt
         * failed validation before reaching any authority check, so it read as "the request is
         * malformed" rather than "this role is not creatable". Nobody would guess.
         *
         * `admin` is the new tier (RoleHierarchy). Being IN this pattern only means the string is
         * a real role; whether THIS caller may create it is StaffAccountScope's question, and it
         * refuses anyone who does not outrank the role being created.
         */
        @NotBlank @Pattern(regexp = "^(admin|ops_admin|support_lead|support_agent|finance_admin|read_only|super_admin)$",
                message = "role must be one of: ops_admin, support_agent, finance_admin, read_only, super_admin")
        String role
    ) {}

    /**
     * Returned once, at creation. The code is never retrievable afterwards — it's hashed at
     * rest — so the master admin must pass it on now. Same rule as the salon invites.
     */
    public record EmployeeCreatedResponse(
        StaffProfile staff,
        String activationCode,
        Instant activationExpiresAt,
        String shareInstructions
    ) {}

    public record StaffStatusChangeRequest(
        @NotBlank @Pattern(regexp = "^(active|suspended|offboarded)$") String status,
        String reason
    ) {}

    /**
     * Correcting a staff member's identity. Session 65.
     *
     * <p>Every field OPTIONAL and null means "leave alone" — a partial edit must not blank the
     * fields it did not send, which is the same rule UpdateMemberRequest follows.
     *
     * <p>Validation here is shape only. WHO may change WHICH field is decided in
     * StaffAdminService.updateIdentity, because it depends on the caller's permissions and on the
     * target's rank — neither of which a Bean Validation annotation can see.
     */
    public record StaffIdentityRequest(
        @Size(max = 120) String name,
        /* Same pattern as CreateEmployeeRequest — +91 and ten digits, no double-91. */
        @Pattern(regexp = "^\\+91[6-9][0-9]{9}$|^$", message = "Use +91 followed by a ten-digit mobile number.")
        String phone,
        @Email @Size(max = 160) String email,
        /** Why. Optional, and it lands in the audit entry — "corrected typo" beats a bare diff. */
        @Size(max = 300) String reason
    ) {}

    /** Promote or demote. The reason is mandatory — a change of authority gets read back later. */
    public record StaffRoleChangeRequest(
        @NotBlank String role,
        @NotBlank @Size(min = 5, max = 300) String reason
    ) {}

    /**
     * What one role can do, for the screen that assigns roles. Session 65.
     *
     * @param canAssign whether the CALLER may hand this role out. Computed per caller, so the
     *                  picker shows the whole ladder — you can see what a main admin is without
     *                  being able to create one — rather than hiding rungs and leaving somebody to
     *                  wonder whether the role exists.
     */
    public record RolePowers(
        String role, String label, int rank, short supportTier,
        List<String> permissions, boolean canAssign
    ) {}

    public record StaffListResponse(List<StaffProfile> staff) {}
}
