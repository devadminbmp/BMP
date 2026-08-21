package com.bmp.auth.controllers;

import com.bmp.auth.client.UserServiceClient;
import com.bmp.auth.dto.OtpRequestRequest;
import com.bmp.auth.dto.UserDto;
import com.bmp.auth.entities.OtpRequests;
import com.bmp.auth.repositories.OtpRequestsRepository;
import com.bmp.auth.repositories.RefreshTokensRepository;
import com.bmp.auth.services.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal endpoints for the staff console — {@code ROLE_SERVICE} only, called by bmp-admin.
 *
 * <h2>Why these exist</h2>
 * "My password isn't working" is the most common support call, and BMP has no passwords: login
 * is a phone number and an emailed code. So the real causes are locked-out, code-never-arrived,
 * deactivated, or wrong-number — and until now a support agent could see none of that. They
 * were guessing.
 *
 * <h2>Why they're here and not on the console's own service</h2>
 * OTP state belongs to bmp-auth. bmp-admin asks; it doesn't reach into another service's tables.
 * And routing through bmp-admin means every one of these lookups lands in the audit log with a
 * staff member's name on it, which a direct console→auth call would not.
 *
 * <h2>What support deliberately CANNOT do here</h2>
 * There is no endpoint to change where a code is delivered, and no endpoint that returns a code.
 * Redirecting a login code is account takeover with extra steps; being able to read one is
 * worse. Support can only clear a lockout and re-send to the address already on the account.
 */
@Tag(name = "Internal auth (staff console)", description = "Service-to-service only. Lets the support console see why a customer can't sign in, and clear a lockout — never read or redirect a code.")
@RestController
@RequestMapping("/api/v1/auth/internal")
@PreAuthorize("hasRole('SERVICE')")
public class InternalAuthController {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthController.class);

    private final OtpRequestsRepository otpRepo;
    private final RefreshTokensRepository refreshRepo;
    private final UserServiceClient users;
    private final AuthService authService;

    public InternalAuthController(OtpRequestsRepository otpRepo, RefreshTokensRepository refreshRepo,
                                  UserServiceClient users, AuthService authService) {
        this.otpRepo = otpRepo;
        this.refreshRepo = refreshRepo;
        this.users = users;
        this.authService = authService;
    }

    /**
     * @param otpLockedUntil   non-null while a lockout is in force
     * @param failedAttempts   wrong codes against the most recent request
     * @param lastOtpSentAt    when we last sent one
     * @param lastOtpExpiresAt so an agent can say "the code you have has expired, I'll send another"
     * @param hasActiveSession whether any refresh token is still alive — "are they signed in
     *                         somewhere already?" is a genuinely useful question when someone
     *                         says they've been logged out
     */
    public record OtpStateResponse(
        UUID userId, String phone,
        Instant otpLockedUntil, int failedAttempts,
        Instant lastOtpSentAt, Instant lastOtpExpiresAt,
        boolean hasActiveSession
    ) {}

    @Operation(
        summary = "Why can't this customer sign in?",
        description = "Lockout state and recent code history for the user's phone. Never returns the code itself — an internal tool that can read a live login code is a tool that can impersonate anyone.")
    @GetMapping("/otp-state/{userId}")
    public OtpStateResponse otpState(@PathVariable UUID userId) {
        UserDto user = requireUser(userId);

        OtpRequests latest = otpRepo.findTopByPhoneOrderByCreatedAtDesc(user.phone()).orElse(null);
        boolean hasSession = !refreshRepo.findByUserIdAndRevokedFalse(userId).isEmpty();

        if (latest == null) {
            // No code has ever been requested for this number. Worth distinguishing: it means
            // they've never tried, not that something failed.
            return new OtpStateResponse(userId, user.phone(), null, 0, null, null, hasSession);
        }

        return new OtpStateResponse(
                userId, user.phone(),
                latest.getLockedUntil(), latest.getAttempts(),
                latest.getCreatedAt(), latest.getExpiresAt(),
                hasSession);
    }

    /**
     * Clear an OTP lockout.
     *
     * <p>Deliberately does NOT issue a code — it only removes the barrier so the customer can
     * request one themselves, through the normal flow, on their own device. Keeping those two
     * actions separate means an agent can help someone who is genuinely locked out without ever
     * causing a login attempt the customer didn't initiate.
     *
     * <p>bmp-admin requires a justification before calling this and records it. That matters
     * more than it looks: unlocking on request is exactly the step a social engineer wants an
     * agent to take.
     */
    @Operation(summary = "Clear a lockout", description = "Removes the barrier; does not send a code. Audited by bmp-admin with the agent's reason.")
    @PostMapping("/unlock/{userId}")
    @Transactional
    public ResponseEntity<Void> unlock(@PathVariable UUID userId) {
        UserDto user = requireUser(userId);

        otpRepo.findTopByPhoneOrderByCreatedAtDesc(user.phone()).ifPresent(otp -> {
            otp.setLockedUntil(null);
            otp.setAttempts(0);
            otpRepo.save(otp);
        });

        log.info("OTP lockout cleared for userId={} (requested via staff console)", userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Send a fresh login code.
     *
     * <p>ALWAYS to the phone and email already on the account — the request carries no address,
     * so there is nothing for a caller to redirect. That's the whole security property: an agent
     * (or anyone who compromises one) cannot point a login code at an address they control.
     *
     * <p>Reuses the ordinary OTP request path rather than a bespoke one, so rate limits,
     * expiry and delivery behave identically to a customer pressing the button themselves.
     */
    @Operation(summary = "Re-send a login code", description = "To the address already on the account. The caller cannot specify a destination — that is the point.")
    @PostMapping("/resend-otp/{userId}")
    public ResponseEntity<Void> resendOtp(@PathVariable UUID userId) {
        UserDto user = requireUser(userId);

        if (user.email() == null || user.email().isBlank()) {
            // Codes are emailed today (SMS is blocked on DLT registration), so no email means
            // no possible delivery. Say so rather than reporting a success nobody receives.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "NO_EMAIL_ON_ACCOUNT: codes are delivered by email and this account has none.");
        }

        authService.requestOtp(new OtpRequestRequest(user.phone(), user.email()));
        log.info("Login code re-sent for userId={} (requested via staff console)", userId);
        return ResponseEntity.noContent().build();
    }

    private UserDto requireUser(UUID userId) {
        UserDto user = users.getUserById(userId).getBody();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }
        return user;
    }
}
