package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Instant;
import java.util.UUID;

/**
 * Reads and clears login state for the support console.
 *
 * <p>"My password isn't working" is the most common support call, and BMP has no passwords —
 * login is a phone number and an emailed code. These three calls turn a guess into a diagnosis.
 *
 * <p>Note what's absent: nothing returns a code, and nothing accepts a delivery address.
 * Redirecting a login code is account takeover with extra steps; being able to read one is
 * worse. Support can only clear a lockout and re-send to the address already on file.
 */
@FeignClient(name = "bmp-auth-service", configuration = com.bmp.admin.config.FeignInternalKeyConfig.class)
public interface AuthServiceClient {

    /**
     * @param otpLockedUntil   non-null while a lockout is in force
     * @param hasActiveSession whether they're signed in somewhere already — genuinely useful
     *                         when someone insists they've been logged out
     */
    record OtpState(
        UUID userId, String phone,
        Instant otpLockedUntil, int failedAttempts,
        Instant lastOtpSentAt, Instant lastOtpExpiresAt,
        boolean hasActiveSession
    ) {}

    @GetMapping("/api/v1/auth/internal/otp-state/{userId}")
    OtpState otpState(@PathVariable("userId") UUID userId);

    /** Clears the barrier. Does NOT send a code — those are deliberately separate actions. */
    @PostMapping("/api/v1/auth/internal/unlock/{userId}")
    void unlock(@PathVariable("userId") UUID userId);

    /** Sends to the address already on the account. There is no destination parameter. */
    @PostMapping("/api/v1/auth/internal/resend-otp/{userId}")
    void resendOtp(@PathVariable("userId") UUID userId);
}
