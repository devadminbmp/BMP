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
 *
 * <p>Session 14: {@code role} and {@code salonId} added to the {@code linked=true} case, so
 * a Google login gives the frontend the same routing info an OTP login does (both null when
 * {@code linked=false}, since no user/role exists yet). Google sign-in is customers-only
 * today, so {@code role} will practically always be {@code customer} here — but it's
 * populated from the real user record, not hardcoded, in case that ever changes.
 *
 * @param linked       true = existing linked account (tokens populated); false = first-seen (only email/googleSubject populated)
 * @param userId       the user's id (null when linked=false)
 * @param role         customer / salon_owner / manager / stylist (null when linked=false)
 * @param salonId      salon scope for owner/manager (null otherwise, and when linked=false)
 * @param refreshToken opaque refresh token (null when linked=false)
 * @param accessToken  JWT bearer token (null when linked=false)
 * @param expiresIn    access-token lifetime in seconds (0 when linked=false)
 * @param email        Google account email (always populated — pre-fill the signup form with it)
 * @param googleSubject Google's stable subject id (always populated — pass back on /otp/verify to link)
 */
public record GoogleAuthResponse(
    boolean linked,
    UUID userId,
    String role,
    UUID salonId,
    String refreshToken,
    String accessToken,
    long expiresIn,
    String email,
    String googleSubject
) {}
