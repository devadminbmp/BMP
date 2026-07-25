package com.bmp.auth.dto;

import java.util.UUID;

/**
 * Response to {@code GET /api/v1/auth/me} (Session 14). The "who am I" call the frontend
 * makes on app startup when it has a stored access token, to restore the session and decide
 * which UI to show — one round-trip instead of decoding the JWT client-side and then
 * separately fetching the profile.
 *
 * <p>{@code role} and {@code salonId} come from the presented JWT (authoritative for THIS
 * session); the remaining profile fields are fetched live from bmp-user. If you need the
 * fuller profile (gender, age, photo, hair type/length), call
 * {@code GET /api/v1/users/{userId}} — this is the identity-focused subset.
 *
 * @param userId     the authenticated user's id
 * @param phone      their E.164 phone (identity key)
 * @param name       display name (may be null if never set)
 * @param email      email on file
 * @param role       customer / salon_owner / manager / stylist (from the JWT)
 * @param salonId    salon scope for owner/manager; null otherwise (from the JWT)
 * @param isVerified whether the account is verified (always true in practice — every account is post-OTP)
 */
public record MeResponse(
    UUID userId,
    String phone,
    String name,
    String email,
    String role,
    UUID salonId,
    boolean isVerified
) {}
