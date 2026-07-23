package com.bmp.salon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * Session 6: salon_schema.salon_staff / staff_invites — owner/manager onboarding.
 * Stylist onboarding does NOT use invites (see StylistDtos.LinkStylistRequest instead;
 * staff_invites' locked columns have no room for a role distinction, so this pass keeps
 * invites scoped to MANAGER only, matching what the schema already supports).
 */
public final class StaffDtos {
    private StaffDtos() {}

    public record CreateInviteRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +919876543210")
        String phone
    ) {}

    public record InviteResponse(UUID id, UUID salonId, String phone, String token, String status, Instant expiresAt) {}

    /** Called by bmp-auth (Feign, internal) during a manager's signup — never exposed to the public gateway route. */
    public record ConsumeInviteRequest(@NotBlank String token, @NotBlank String phone, @NotBlank String userId) {}

    public record ConsumeInviteResponse(UUID salonId) {}

    /** Called by bmp-auth (Feign, internal) on every token mint to resolve a non-customer
     * user's current salon scope. 204/empty body means "no staff seat" (e.g. a fresh
     * SALON_OWNER signup that hasn't created a salon yet). */
    public record StaffLookupResponse(UUID salonId, String role) {}
}
