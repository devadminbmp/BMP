package com.bmp.auth.dto;

import java.util.UUID;

/** Mirrors bmp-user-service's user response shape. Kept minimal to what auth needs.
 * Session 6: added email (dual-channel OTP needs to know where to send). */
public record UserDto(UUID id, String phone, String email, String name, String defaultRole, boolean isVerified) {}
