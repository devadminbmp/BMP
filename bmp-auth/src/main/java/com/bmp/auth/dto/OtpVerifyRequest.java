package com.bmp.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Session 6: signup fields, all optional and only meaningful the FIRST time a phone
 * verifies (see AuthService.findOrCreateUser) — ignored for an existing user's login:
 * <ul>
 *   <li>{@code email} — required by AuthService for a brand-new signup (dual-channel OTP
 *       needs it); an existing user's email on file is used instead.</li>
 *   <li>{@code role} — CUSTOMER (default if omitted), SALON_OWNER, MANAGER, or STYLIST.</li>
 *   <li>{@code name} — used for STYLIST signup (their portable Stylist profile needs a
 *       display name); ignored for other roles in this pass.</li>
 *   <li>{@code inviteToken} — REQUIRED when role=MANAGER (see bmp-salon's staff_invites);
 *       not applicable to any other role.</li>
 *   <li>{@code googleSubject} — set only when completing a Google-first signup (see
 *       GoogleAuthResponse.linked=false) — links the new user to that Google account in the
 *       same transaction that creates it.</li>
 * </ul>
 */
public record OtpVerifyRequest(
    @NotBlank String phone,
    @NotBlank String otp,
    String deviceFingerprint,
    @Email String email,
    String role,
    String name,
    String inviteToken,
    String googleSubject
) {}
