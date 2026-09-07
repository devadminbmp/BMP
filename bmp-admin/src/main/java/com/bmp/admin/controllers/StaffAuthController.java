package com.bmp.admin.controllers;

import com.bmp.admin.dto.AdminAuthDtos.*;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.services.StaffAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Console authentication.
 *
 * <p>Mounted under {@code /api/v1/admin/**}, which {@link com.bmp.admin.security.AdminSecurityConfig}
 * routes through the STAFF filter chain — a different signing key and a different token audience
 * from every customer-facing service. A customer token presented here is not merely refused by
 * an authorization rule; nothing in this chain can parse it.
 *
 * <p>Login is two calls by design: {@code /login} proves the password and returns a short-lived
 * challenge token, then {@code /totp/verify} proves the second factor and returns a session.
 */
@Tag(name = "Console auth", description = "Staff sign-in for the internal console. Separate identity, separate tokens, separate service from customer auth.")
@RestController
@RequestMapping("/api/v1/admin/auth")
public class StaffAuthController {

    private final StaffAuthService auth;

    public StaffAuthController(StaffAuthService auth) {
        this.auth = auth;
    }

    @Operation(
        summary = "Step 1 — password",
        description = "Returns TOTP_REQUIRED with a short-lived challenge token, or TOTP_ENROLMENT_REQUIRED with a provisioning URI on first login. Never returns a session. Every failure — unknown email, wrong password, suspended, never activated — returns the same 401 with the same wording, so this endpoint cannot be used to discover which emails are staff.")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req.email(), req.password(), clientIp(http));
    }

    @Operation(summary = "Step 2 — two-factor code", description = "Consumes the challenge token from step 1 and returns an access token plus a revocable refresh token.")
    @PostMapping("/totp/verify")
    public LoginResponse verifyTotp(@Valid @RequestBody TotpRequest req, HttpServletRequest http) {
        return auth.verifyTotp(req.challengeToken(), req.code(), clientIp(http), http.getHeader("User-Agent"));
    }

    @Operation(
        summary = "First login — confirm two-factor enrolment",
        description = "The secret is only persisted once a valid code proves the authenticator app actually has it. Abandoning enrolment therefore leaves the account exactly as it was, rather than locked out of a second factor it thinks it has.")
    @PostMapping("/totp/enrol")
    public LoginResponse enrolTotp(@Valid @RequestBody TotpRequest req, HttpServletRequest http) {
        return auth.enrolTotp(req.challengeToken(), req.code(), clientIp(http), http.getHeader("User-Agent"));
    }

    @Operation(
        summary = "Redeem an activation code and set a password",
        description = "How a new employee turns the one-time code their master admin gave them into a working account. They choose their own password — nobody else ever sees it. Two-factor is set up on their first login afterwards.")
    @PostMapping("/activate")
    public ResponseEntity<Void> activate(@Valid @RequestBody ActivateRequest req, HttpServletRequest http) {
        auth.activate(req.activationCode(), req.newPassword(), clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Exchange a refresh token for a new access token", description = "Re-checks that the account is still active, so suspending someone takes effect within one token lifetime.")
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return auth.refresh(req.refreshToken(), clientIp(http), http.getHeader("User-Agent"));
    }

    @Operation(summary = "Who am I", description = "Includes the permission set the console uses to decide what to SHOW. Every endpoint enforces independently.")
    @GetMapping("/me")
    public StaffProfile me(@AuthenticationPrincipal StaffPrincipal caller) {
        return auth.profileOf(caller.staffId());
    }

    @Operation(
        summary = "Change my own password",
        description = """
            Requires your CURRENT password AND a live code from your authenticator app. Both,             deliberately: if only the password were needed, anybody who stole it could change it             and lock you out permanently, and your two-factor would never get a chance to matter.

            EVERY SESSION ENDS, including the one making this call — you will be signed out and             need to sign in again with the new password. A password change is what people do when             they think somebody else has been in the account, so "all sessions ended" has to be             true without an asterisk.

            This is NOT the lockout path. If you cannot sign in at all, an ops admin re-issues             your credentials — that is the only recovery route, because a reset link landing in a             compromised inbox defeats two-factor entirely.""")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                @AuthenticationPrincipal StaffPrincipal caller,
                                                HttpServletRequest http) {
        auth.changeOwnPassword(caller.staffId(), req.currentPassword(), req.totpCode(),
                req.newPassword(), clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Update my own name or phone",
        description = """
            Your name and your phone number, and nothing else.

            Your work EMAIL is not here: it is your sign-in address, so somebody who took over a             session could otherwise point the account at an inbox they control and keep it. An ops             admin changes that.

            Role, tier and account status are not here either — that would be self-promotion. Job             title and reporting line belong to whoever manages you, on the Team screen.""")
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/me/profile")
    public StaffProfile updateOwnProfile(@Valid @RequestBody UpdateOwnProfileRequest req,
                                          @AuthenticationPrincipal StaffPrincipal caller,
                                          HttpServletRequest http) {
        return auth.updateOwnProfile(caller.staffId(), req.name(), req.phone(), clientIp(http));
    }

    @Operation(summary = "Sign out", description = "Revokes the refresh token server-side. With no token supplied, revokes every session for this staff member.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal StaffPrincipal caller,
                                        @RequestBody(required = false) RefreshRequest req) {
        auth.logout(caller.staffId(), req == null ? null : req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * The caller's IP, for the audit trail.
     *
     * <p>Reads {@code X-Forwarded-For} because the gateway sits in front — but trusts it ONLY
     * because nothing security-relevant depends on it. It's recorded for investigation, never
     * used to grant access, so a spoofed header misleads a reader rather than bypassing a
     * control. TODO(infra): once the proxy chain is fixed, configure a trusted-proxy list.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
