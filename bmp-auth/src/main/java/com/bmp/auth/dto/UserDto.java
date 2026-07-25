package com.bmp.auth.dto;

import java.time.Instant;
import java.util.UUID;

/** Mirrors bmp-user-service's user response shape. Kept minimal to what auth needs.
 * Session 6: added email (dual-channel OTP needs to know where to send).
 * Session 13: added deactivatedAt — a non-null value on login triggers auto-reactivation
 * (the fresh OTP login IS the "I want my account back" signal; see AuthService). */
public record UserDto(UUID id, String phone, String email, String name, String defaultRole,
                       boolean isVerified, Instant deactivatedAt) {}
