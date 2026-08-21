package com.bmp.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Console-facing shapes for moderation, users, data requests and the ops overview.
 *
 * <h2>PII is masked HERE, at the boundary</h2>
 * {@link UserSummaryResponse} carries {@code phoneMasked}, never {@code phone}. Revealing costs
 * a separate, justified call that writes an audit entry.
 *
 * <p>That's a deliberate architectural choice rather than a UI one: if masking happened in the
 * browser, the real value would still have crossed the network, sat in the response cache and
 * appeared in anyone's devtools. Masking is only meaningful if the unmasked value never leaves
 * the server without a reason attached.
 */
public final class ConsoleDtos {
    private ConsoleDtos() {}

    // ---- ops overview ---------------------------------------------------------------------

    public record OpsSummaryResponse(
        long salonsPendingReview,
        long ticketsOpen,
        long ticketsBreachingSla,
        long dataRequestsDueSoon,
        long contentReportsOpen,
        long bookingsToday,
        boolean newBookingsEnabled
    ) {}

    // ---- salon moderation ------------------------------------------------------------------

    public record SalonReviewResponse(
        UUID id, UUID salonId, String salonName, String ownerName, String area,
        String status, Instant submittedAt, Instant decidedAt, String decidedByName,
        String decisionNote, Map<String, Boolean> checks
    ) {}

    /**
     * @param decision approved | rejected | suspended
     * @param note     REQUIRED for rejection — "no" without a reason generates a support ticket
     *                 every single time, and the owner has no idea what to fix
     * @param checks   what the reviewer actually verified, recorded against their name
     */
    public record SalonDecisionRequest(
        @NotBlank @Pattern(regexp = "^(approved|rejected|suspended)$") String decision,
        String note,
        Map<String, Boolean> checks
    ) {}

    // ---- users ------------------------------------------------------------------------------

    public record UserSummaryResponse(
        UUID id, String name, String phoneMasked, String emailMasked, String role,
        boolean isVerified, Instant deactivatedAt, Instant createdAt, Long bookingCount
    ) {}

    /**
     * Reveal exactly ONE field, with a reason.
     *
     * <p>Deliberately not a "show everything" toggle: the narrower the request, the more
     * meaningful the audit entry. "Revealed the phone number to call them back about ticket
     * TCK-42" is reviewable; "unmasked the record" is not.
     */
    public record RevealPiiRequest(
        @NotBlank @Pattern(regexp = "^(phone|email)$") String field,
        @NotBlank @Size(min = 10, message = "say why you need it — at least a few words")
        String justification
    ) {}

    public record RevealPiiResponse(String value) {}

    /**
     * Why a customer can't sign in.
     *
     * <p>BMP has no passwords — login is a phone number and an emailed code — so this exposes
     * the four things that actually go wrong, separately, because each has a different fix.
     */
    public record AccountHealthResponse(
        UUID userId, String name, String phoneMasked, String emailMasked, String role,
        boolean isVerified, Instant deactivatedAt,
        Instant otpLockedUntil, int failedOtpAttempts, Instant lastOtpSentAt,
        boolean lastEmailDeliveryFailed, Instant lastLoginAt, boolean googleLinked
    ) {}

    public record JustifiedActionRequest(
        @NotBlank @Size(min = 10, message = "say why — at least a few words") String justification
    ) {}

    // ---- data requests -----------------------------------------------------------------------

    public record DataRequestResponse(
        UUID id, String requestType, UUID subjectUserId, String subjectEmail, String status,
        Instant identityVerifiedAt, Instant dueAt, Instant completedAt, String notes, Instant createdAt
    ) {}

    public record CreateDataRequestRequest(
        @NotBlank @Pattern(regexp = "^(export|delete|correct)$") String requestType,
        @NotNull UUID subjectUserId,
        String notes
    ) {}

    public record DataRequestActionRequest(String note) {}

    // ---- audit ---------------------------------------------------------------------------------

    public record AuditEntryResponse(
        UUID id, String actorEmail, String actorRole, String action,
        String entityType, UUID entityId, String justification, String ipAddress, Instant createdAt
    ) {}

    // ---- settings --------------------------------------------------------------------------------

    public record SettingChangeRequest(
        @NotBlank String value,
        @NotBlank @Size(min = 8, message = "say why — this is recorded against your name")
        String justification
    ) {}

    public record SettingResponse(String key, String value, String valueType, String description) {}

    public record SettingsListResponse(List<SettingResponse> settings) {}
}
