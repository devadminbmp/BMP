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
 *   <li>{@code loginOnly} — see below. NOT a signup field; it's the opposite.</li>
 * </ul>
 *
 * <h2>Session 43: {@code loginOnly}</h2>
 * This endpoint signs a user up when the phone is unknown, which is right for the customer and
 * salon-owner doors — those ARE signup flows. It is wrong for a door labelled <b>"Staff sign
 * in"</b>. There, an unknown phone means the person mistyped their number, and the correct
 * response is to say so, not to quietly create a fresh CUSTOMER account for the typo and then
 * tell them they aren't staff.
 *
 * <p>Today that outcome is prevented only by accident: {@code role} defaults to {@code customer}
 * and the staff door happens not to send an {@code email}, so creation fails on the missing
 * email first. That is a guard nobody designed, sitting one small edit away from disappearing —
 * add an email to that call for any reason and the account gets created.
 *
 * <p>So the intent is stated explicitly instead of being inferred from which fields happen to be
 * absent. {@code loginOnly=true} means: <i>authenticate an existing account or fail; never
 * create one.</i> Defaults to {@code false} (a plain {@code Boolean}, so an older client that
 * omits it keeps today's signup behaviour and nothing breaks).
 *
 * <p>The general rule this follows: <b>when behaviour depends on intent, send the intent.</b>
 * Deducing it from the shape of the payload works right up until the payload changes for an
 * unrelated reason.
 */
public record OtpVerifyRequest(
    @NotBlank String phone,
    @NotBlank String otp,
    String deviceFingerprint,
    @Email String email,
    String role,
    String name,
    String inviteToken,
    String googleSubject,
    Boolean loginOnly
) {
    /** Null-safe read — absent means "signup allowed", i.e. the pre-Session-43 behaviour. */
    public boolean isLoginOnly() {
        return Boolean.TRUE.equals(loginOnly);
    }
}
