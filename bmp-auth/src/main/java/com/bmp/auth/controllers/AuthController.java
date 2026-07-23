package com.bmp.auth.controllers;

import com.bmp.auth.dto.*;
import com.bmp.auth.services.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public entry points for login. Reached through api-gateway at /api/v1/auth/**.
 * See BMP-31 (Phase 3 tracker task) for the original full contract.
 */
@Tag(name = "Auth", description = "OTP request/verify, Google sign-in, refresh, logout. No token required on this controller — it's what ISSUES the token.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(
        summary = "Request an OTP",
        description = """
            Sends a 6-digit code to BOTH the phone (SMS/WhatsApp) and email on file — \
            dual-channel by design, not one-or-the-other. `email` is required the first \
            time a phone number is seen (nothing to look up yet); for an existing user \
            it's ignored and the stored email is reused. Rate-limited to one request per \
            phone per 55 seconds. Code expires in 5 minutes, 3 wrong attempts locks it \
            for 10 minutes.""")
    @SecurityRequirements
    @PostMapping("/otp/request")
    public ResponseEntity<OtpRequestResponse> requestOtp(@Valid @RequestBody OtpRequestRequest req) {
        return ResponseEntity.ok(authService.requestOtp(req));
    }

    @Operation(
        summary = "Verify an OTP — logs in an existing user, or signs up a new one",
        description = """
            For an EXISTING phone number: `role`/`email`/`name`/`inviteToken` are all \
            ignored, you're just logging in as whatever role that account already has. \

            For a BRAND-NEW phone number, this is signup: `role` defaults to CUSTOMER if \
            omitted, or set SALON_OWNER / MANAGER / STYLIST. `email` is required. \
            MANAGER additionally requires `inviteToken` (from a salon owner's \
            POST /api/v1/salons/{salonId}/invites). STYLIST additionally uses `name` for \
            their portable Stylist profile. SALON_OWNER needs nothing else — create a \
            salon afterward as an authenticated call and you become its OWNER \
            automatically.

            Returns a short-lived access token (JWT, ~15 min) and a long-lived opaque \
            refresh token (~30 days, `selector.verifier` format).""")
    @SecurityRequirements
    @PostMapping("/otp/verify")
    public ResponseEntity<OtpVerifyResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return ResponseEntity.ok(authService.verifyOtp(req));
    }

    @Operation(
        summary = "Sign in with Google (customers only)",
        description = """
            Verifies a Google ID token the CLIENT already obtained via Google Sign-In \
            SDK — this endpoint does not itself drive an OAuth redirect. Returns 501 \
            until BMP_GOOGLE_CLIENT_ID is configured (no Google Cloud OAuth client \
            exists yet).

            If this Google account is already linked to a bmp user: `linked=true` plus \
            a full token pair, same shape as /otp/verify.

            If it's the FIRST time this Google account has been seen: `linked=false`, \
            only `email`/`googleSubject` are populated — there's no phone number \
            available from Google and `users.phone` is NOT NULL, so nothing is created \
            yet. The client should collect a phone number and go through the normal \
            /otp/request + /otp/verify signup, passing the returned `email` and \
            `googleSubject` through on /otp/verify to link the two accounts.""")
    @SecurityRequirements
    @PostMapping("/oauth2/google")
    public ResponseEntity<GoogleAuthResponse> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest req) {
        return ResponseEntity.ok(authService.loginWithGoogle(req));
    }

    @Operation(
        summary = "Exchange a refresh token for a new access token",
        description = "Also re-resolves the user's CURRENT role and salon scope from bmp-user/bmp-salon, so a stale JWT claim (e.g. an owner who just created their first salon) self-corrects on the next refresh instead of waiting for a full re-login.")
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(authService.refresh(req.refreshToken()));
    }

    @Operation(summary = "Revoke a refresh token", description = "Does not invalidate any already-issued access token — those simply expire on their own short TTL (~15 min).")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @SecurityRequirements
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
