package com.bmp.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /**
     * @param submissionCount   which attempt this is. V007 (Session 46) — a salon now has one
     *                          review row per submission, so a moderator can tell a second look
     *                          from a first and a rejection is never overwritten by a retry.
     * @param resubmissionNote  what the owner says they fixed. Null on a first submission.
     *                          Without it a reviewer re-checks the whole salon from scratch,
     *                          which is how resubmissions end up slower than new salons — and
     *                          punishes exactly the partners who did what we asked.
     */
    /** One service on the review screen. Archived is flagged, not hidden — see bmp-salon. */
    public record ReviewServiceItem(String name, long pricePaise, int durationMinutes, boolean archived) {}
    public record ReviewPhoto(String url, String caption) {}

    /**
     * A salon awaiting review, with enough on it to actually decide. Session 48.
     *
     * <h2>What this used to be</h2>
     * The same record without `detail` — and with `ownerName` and `area` passed as literal
     * {@code null} by toResponse(). A moderator was asked to tick "the address matches the map
     * pin" and "the photos are of this salon" while looking at a name and a UUID. The checklist
     * referred to things the screen did not show.
     *
     * <p>`detail` is nullable: bmp-salon being unreachable degrades the row to ids rather than
     * failing the whole queue, which is the same trade the original enrichment made.
     */
    public record SalonReviewDetail(
        String reference, String area, String pincode, String address, String about,
        String imageUrl, Double lat, Double lng, String mapLink,
        java.util.List<String> categories,
        /**
         * Session 65 — the owner's USER ID, and it is the point of this whole record now.
         *
         * <p>bmp-salon has always returned it on the moderation packet and bmp-admin has always
         * thrown it away, so the console could SHOW an owner's name, email and phone and could not
         * DO anything about any of them. Darshan: <i>"nowhere i see salon edit profile can be done
         * ... this critical"</i> — the salon's own fields were editable since Session 65; the
         * person behind it was not reachable from the salon at all.
         *
         * <p>With the id present, the console can open the same AccountAdminPanel it uses on the
         * Users page. Nothing new is authorised: a salon owner is a `salon_owner` row in
         * user_schema.users, so AccountScope already puts them behind {@code account:manage_staff}
         * — ops and above. This closes a NAVIGATION gap, not a permission one.
         *
         * <p>Nullable: a salon whose owner seat was deleted directly in SQL has no owner. The
         * console shows the salon rather than failing, and an owner-less salon is its own problem
         * (see tools/one-salon-per-owner-doctor.sql, section 4).
         */
        UUID ownerUserId,
        String ownerName, String ownerEmail, String ownerPhone,
        int stylistCount,
        java.util.List<ReviewServiceItem> services,
        java.util.List<ReviewPhoto> photos,
        Instant salonCreatedAt
    ) {}

    public record SalonReviewResponse(
        UUID id, UUID salonId, String salonName, String ownerName, String area,
        String status, Instant submittedAt, Instant decidedAt, String decidedByName,
        String decisionNote, Map<String, Boolean> checks,
        int submissionCount, String resubmissionNote,
        SalonReviewDetail detail
    ) {}

    /**
     * @param decision approved | rejected | suspended
     * @param note     REQUIRED for rejection — "no" without a reason generates a support ticket
     *                 every single time, and the owner has no idea what to fix
     * @param checks   what the reviewer actually verified, recorded against their name
     */
    /**
     * Freeze / restore / remove, outside the review flow. Session 48.
     *
     * @param note REQUIRED. The owner is emailed this verbatim. A salon taken off the site with no
     *             explanation generates a support ticket every single time, and the person reading
     *             it has no idea what happened either — the note is the only record of why.
     */
    public record SalonStatusRequest(
        @NotBlank @Pattern(regexp = "^(active|suspended|deleted)$") String status,
        @NotBlank @Size(min = 10, max = 1000) String note
    ) {}

    public record SalonDecisionRequest(
        @NotBlank @Pattern(regexp = "^(approved|rejected|suspended)$") String decision,
        String note,
        Map<String, Boolean> checks,
        /**
         * Platform commission in basis points, set at the moment of approval. Session 48.
         *
         * <p>NULL means "leave it as it is" — which for a new salon is V009's 1200 (12%) default.
         * That is deliberately not the same as 0: a reviewer who ticks through the form without
         * touching this field must not accidentally sign a salon up for zero commission, and a
         * field that defaults to 0 when omitted would do exactly that on the first day somebody
         * writes a client that doesn't send it.
         *
         * <p>Only read on an APPROVAL. Rejections and suspensions ignore it — there is no rate to
         * agree with a salon we are not taking on.
         *
         * <p>Editable afterwards through the salon's policy endpoint; this is the opening rate,
         * not a permanent one. Launch offers ("no commission for your first month") are the
         * expected case, which is why it is per-salon rather than a constant.
         */
        @Min(0) @Max(5000) Integer commissionBps
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
