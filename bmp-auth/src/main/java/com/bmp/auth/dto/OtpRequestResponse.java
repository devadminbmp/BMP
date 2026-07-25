package com.bmp.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response to {@code POST /api/v1/auth/otp/request}.
 *
 * <p>Session 14: {@code resendAvailableAt} added so the frontend can show an accurate
 * "resend code in Ns" countdown instead of hardcoding the cooldown. Requesting another OTP
 * for the same phone before this instant returns HTTP 429 (see AuthService — cooldown is
 * {@code AuthService.OTP_RESEND_COOLDOWN_SECONDS}).
 *
 * @param otpRequestId      id of the created otp_requests row (opaque; the client doesn't need it to verify, verification is by phone)
 * @param expiresAt         when the code stops working (~5 min out) — show a "code expires in" timer off this
 * @param resendAvailableAt earliest instant a new /otp/request will be accepted for this phone — drive the resend button off this
 */
public record OtpRequestResponse(UUID otpRequestId, Instant expiresAt, Instant resendAvailableAt) {}
