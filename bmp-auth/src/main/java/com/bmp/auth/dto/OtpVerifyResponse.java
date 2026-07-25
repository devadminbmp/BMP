package com.bmp.auth.dto;

import java.util.UUID;

/**
 * Response to a successful {@code POST /api/v1/auth/otp/verify} — the login/signup result.
 *
 * <p>Session 14: {@code role}, {@code salonId} and {@code isNewUser} were added so the
 * frontend can route the user to the correct home screen WITHOUT having to decode the JWT
 * itself:
 * <ul>
 *   <li>{@code role} — one of {@code customer / salon_owner / manager / stylist} (lowercase,
 *       matching the DB + JWT claim). Decide which app UI to show off this.</li>
 *   <li>{@code salonId} — the salon this session is scoped to, for SALON_OWNER/MANAGER.
 *       {@code null} for CUSTOMER and STYLIST always, and {@code null} for a brand-new
 *       SALON_OWNER who hasn't created their salon yet (they get it on their next
 *       {@code /refresh} after creating one — see AuthService.resolveSalonScope).</li>
 *   <li>{@code isNewUser} — {@code true} if this verify just CREATED the account (signup),
 *       {@code false} if it logged into an existing one. Use it to decide whether to send
 *       the user into onboarding vs straight to home.</li>
 * </ul>
 *
 * @param userId       the user's id (also the JWT {@code sub} claim)
 * @param role         customer / salon_owner / manager / stylist
 * @param salonId      salon scope for owner/manager; null otherwise
 * @param isNewUser    true = just signed up, false = logged into an existing account
 * @param refreshToken opaque, {@code selector.verifier} format, ~30-day TTL
 * @param accessToken  JWT bearer token, ~15-min TTL
 * @param expiresIn    access-token lifetime in seconds
 */
public record OtpVerifyResponse(
    UUID userId,
    String role,
    UUID salonId,
    boolean isNewUser,
    String refreshToken,
    String accessToken,
    long expiresIn
) {}
