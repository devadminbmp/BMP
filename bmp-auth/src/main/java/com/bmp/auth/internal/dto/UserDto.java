package com.bmp.auth.internal.dto;

import java.util.UUID;

/** Mirrors bmp-user-service's user response shape. Kept minimal to what auth needs. */
public record UserDto(UUID id, String phone, String name, String defaultRole, boolean isVerified) {}
