package com.bmp.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** BMP-22 request/response DTOs — user_schema.users + user_roles + onboarding_state.
 * Session 13: validation hardened (gender whitelist, age bounds, email format) and
 * onboarding/default-role/deactivation shapes added. */
public final class UserDtos {
    private UserDtos() {}

    private static final String E164 = "^\\+[1-9]\\d{7,14}$";
    private static final String GENDER = "^(male|female|other)$";
    // Lowercase, matching the DB convention (V002's role comment) and what bmp-auth
    // actually sends ("customer"/"salon_owner"/...). JwtAuthFilter uppercases only the
    // Spring Security AUTHORITY, not the stored value.
    private static final String ROLE = "^(customer|salon_owner|manager|stylist)$";

    public record CreateUserRequest(
        @NotBlank @Pattern(regexp = E164) String phone,
        String name,
        @Pattern(regexp = GENDER, message = "must be male, female or other") String gender,
        @Min(1) @Max(120) Integer age,
        @Email String email,
        @NotBlank @Pattern(regexp = ROLE) String defaultRole
    ) {}

    public record UpdateUserRequest(
        String name,
        @Pattern(regexp = GENDER, message = "must be male, female or other") String gender,
        @Min(1) @Max(120) Integer age,
        @Email String email,
        String profilePhotoUrl, String hairType, String hairLength
    ) {}

    public record UserResponse(
        UUID id, String phone, String name, String gender, Integer age, String email,
        String profilePhotoUrl, String hairType, String hairLength, String defaultRole,
        boolean isVerified, Instant deactivatedAt, Instant createdAt, Instant updatedAt
    ) {}

    public record CreateRoleRequest(@NotBlank @Pattern(regexp = ROLE) String role, UUID salonId) {}

    public record RoleResponse(UUID id, UUID userId, String role, UUID salonId) {}

    /** Session 13: switching which held role the user logs in as by default. */
    public record DefaultRoleRequest(@NotBlank @Pattern(regexp = ROLE) String defaultRole) {}

    /** Session 13: onboarding crash-recovery blob — replaced wholesale on every save,
     * deleted when onboarding completes (per CONTEXT.md Module 1: "not a business data
     * table"). */
    public record OnboardingStateRequest(@NotNull Map<String, Object> state) {}

    public record OnboardingStateResponse(UUID userId, Map<String, Object> state, Instant updatedAt) {}

    public record ErrorResponse(String error, String message) {}
}
