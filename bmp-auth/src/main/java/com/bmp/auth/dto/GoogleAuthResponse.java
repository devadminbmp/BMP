package com.bmp.auth.dto;

import java.util.UUID;

/**
 * {@code linked=true}: this Google account is already tied to a bmp user — {@code userId},
 * {@code refreshToken}, {@code accessToken}, {@code expiresIn} are populated, same shape as
 * a normal OTP login.
 *
 * <p>{@code linked=false}: first time this Google account has been seen. There's no phone
 * number to create a user with (Google doesn't provide one, and {@code users.phone} is
 * NOT NULL — a locked column), so nothing is created yet. The client should collect/confirm
 * a phone number and go through the normal {@code /otp/request} + {@code /otp/verify} signup
 * flow, passing {@code email} (pre-filled from here) and {@code googleSubject} through on
 * {@code /otp/verify} so AuthService links the two in the same transaction that creates the
 * user — see OtpVerifyRequest.googleSubject.
 */
public record GoogleAuthResponse(
    boolean linked,
    UUID userId,
    String refreshToken,
    String accessToken,
    long expiresIn,
    String email,
    String googleSubject
) {}
