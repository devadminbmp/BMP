package com.bmp.auth.dto;

import java.util.UUID;

/**
 * Response to {@code POST /api/v1/auth/refresh}. Only a new access token is issued — the
 * refresh token itself is NOT rotated here, keep using the same one until it expires.
 *
 * <p>Session 14: {@code role} and {@code salonId} added. Refresh re-resolves the user's
 * CURRENT role and salon scope from bmp-user/bmp-salon on every call, so these values
 * self-correct a stale session — e.g. a SALON_OWNER who created their first salon after
 * logging in gets their real {@code salonId} here on the next refresh, without re-logging
 * in. The frontend should re-read role/salonId from this response after every refresh.
 *
 * @param accessToken new JWT bearer token
 * @param expiresIn   its lifetime in seconds
 * @param role        the user's current role (re-resolved live)
 * @param salonId     current salon scope for owner/manager; null otherwise
 */
public record RefreshResponse(String accessToken, long expiresIn, String role, UUID salonId) {}
