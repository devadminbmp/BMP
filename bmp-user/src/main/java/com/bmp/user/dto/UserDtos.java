package com.bmp.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

/** BMP-22 request/response DTOs — user_schema.users + user_roles CRUD. */
public final class UserDtos {
    private UserDtos() {}

    private static final String E164 = "^\\+[1-9]\\d{7,14}$";

    public record CreateUserRequest(
        @NotBlank @Pattern(regexp = E164) String phone,
        String name, String gender, Integer age, String email,
        @NotBlank String defaultRole
    ) {}

    public record UpdateUserRequest(
        String name, String gender, Integer age, String email,
        String profilePhotoUrl, String hairType, String hairLength
    ) {}

    public record UserResponse(
        UUID id, String phone, String name, String gender, Integer age, String email,
        String profilePhotoUrl, String hairType, String hairLength, String defaultRole,
        boolean isVerified, Instant createdAt, Instant updatedAt
    ) {}

    public record CreateRoleRequest(@NotBlank String role, UUID salonId) {}

    public record RoleResponse(UUID id, UUID userId, String role, UUID salonId) {}

    public record ErrorResponse(String error, String message) {}
}
