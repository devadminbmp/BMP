package com.bmp.salon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

    /**
     * Session 17: {@code role} and {@code inviteeName} added.
     *
     * <p>{@code role} is optional and defaults to {@code manager}, so every existing caller
     * keeps working unchanged. {@code stylist} issues an invite that creates a stylist_salon
     * link instead of a salon_staff seat when redeemed.
     *
     * <p>{@code inviteeName} is for the ISSUER's pending list — "+919876543210" means nothing
     * three days later, "Ravi Kumar · +9198…" does. It is never used as the invitee's profile
     * name: the person who typed it isn't the person it describes.
     */
    public record CreateInviteRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +919876543210")
        String phone,
        @Pattern(regexp = "^(manager|stylist)$", message = "role must be manager or stylist")
        String role,
        String inviteeName,
        /*
         * V028 (Session 65) — email the code instead of reading it out.
         *
         * OPTIONAL, and the regexp only runs when something is supplied (an empty string matches
         * nothing here, so blank is normalised to null in the service rather than rejected). An
         * owner inviting somebody standing at the desk has no reason to know their email, and
         * making this required would block the commonest case to serve the convenient one.
         */
        @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$", message = "not a valid email address")
        @Size(max = 160)
        String inviteeEmail
    ) {
        /** Absent role means manager — the only kind that existed before Session 17. */
        public String roleOrDefault() {
            return role == null || role.isBlank() ? "manager" : role;
        }

        /** Blank and absent are the same thing: no address, so nothing to send to. */
        public String emailOrNull() {
            return inviteeEmail == null || inviteeEmail.isBlank() ? null : inviteeEmail.trim();
        }
    }

    /**
     * @param inviteeEmail echoed back so the owner's pending list can say where it went
     * @param emailedAt null with an email present means the send FAILED or has not run — a state
     *                  the owner needs to see, because they are the fallback delivery channel
     */
    public record InviteResponse(UUID id, UUID salonId, String phone, String token, String status,
                                  Instant expiresAt, String role, String inviteeName,
                                  String inviteeEmail, Instant emailedAt) {}

    /**
     * Session 15 — owner-facing team roster.
     *
     * <p>{@code name}/{@code phone} are enriched from bmp-user and are NULLABLE on purpose: if
     * that service is unreachable we still return the seats rather than failing the whole
     * roster, and the UI falls back to a shortened id. A staff list is not worth a 500.
     */
    public record StaffMemberResponse(
        UUID id, UUID userId, String role, String name, String phone, Instant createdAt,
        /** True for the caller's own seat — the UI uses it to hide "remove" on yourself. */
        boolean isSelf,
        /** False for OWNER seats: removing the owner would orphan the salon. */
        boolean removable
    ) {}

    /**
     * Add someone who ALREADY has a BMP account as a manager, without an invite round-trip.
     *
     * <p>Why this exists alongside invites: {@code /otp/verify} ignores {@code role} and
     * {@code inviteToken} for a phone that already exists (it's a login at that point, not a
     * signup). So an invite code sent to an existing customer would silently do nothing — they'd
     * log in and still be a customer. This path covers them; invites cover people with no
     * account yet.
     */
    public record AddStaffRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +919876543210")
        String phone
    ) {}

    /**
     * Called by bmp-auth (Feign, internal) during signup — never exposed to the public gateway
     * route.
     *
     * <p>Session 17: {@code stylistId} added, required only when the invite's role is
     * {@code stylist}. bmp-auth creates the portable Stylist profile first (it already did),
     * then passes its id here so the link can be made. It's the one fact bmp-salon can't derive
     * itself, because the profile is created in the same signup transaction.
     */
    public record ConsumeInviteRequest(@NotBlank String token, @NotBlank String phone,
                                        @NotBlank String userId, UUID stylistId) {
        /** Pre-Session-17 shape, kept so a rolling deploy of bmp-auth can't break. */
        public ConsumeInviteRequest(String token, String phone, String userId) {
            this(token, phone, userId, null);
        }
    }

    /** {@code role} tells bmp-auth what was just created — a staff seat or a stylist link. */
    public record ConsumeInviteResponse(UUID salonId, String role) {
        public ConsumeInviteResponse(UUID salonId) {
            this(salonId, "manager");
        }
    }

    /** Called by bmp-auth (Feign, internal) on every token mint to resolve a non-customer
     * user's current salon scope. 204/empty body means "no staff seat" (e.g. a fresh
     * SALON_OWNER signup that hasn't created a salon yet). */
    public record StaffLookupResponse(UUID salonId, String role) {}
}
