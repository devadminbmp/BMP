package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Reads salon records for the moderation queue and salon-side support.
 *
 * <p>Partial mirrors again — the console needs a name, an area and a staff count, not
 * bmp-salon's full model. Anything richer becomes a coupling that breaks on their next change.
 */
@FeignClient(name = "bmp-salon-service", configuration = com.bmp.admin.config.FeignInternalKeyConfig.class)
public interface SalonServiceClient {

    record SalonDto(UUID id, String name, Object location, String stylistAssignmentStrategy) {}

    /** One gallery photo, as a customer sees it. Session 46 — for the moderation preview. */
    record SalonPhotoDto(java.util.UUID id, java.util.UUID salonId, String url, String caption,
                          int sortOrder) {}

    /**
     * The salon's photos. PUBLIC on bmp-salon, but fetched through this service-to-service client
     * so the console keeps talking only to bmp-admin — one API boundary rather than two.
     */
    @GetMapping("/api/v1/salons/{salonId}/photos")
    java.util.List<SalonPhotoDto> getSalonPhotos(@PathVariable("salonId") java.util.UUID salonId);

    record StylistSalonDto(
        UUID id, UUID stylistId, String stylistName, UUID salonId, String status,
        boolean isAvailableToday
    ) {}

    @GetMapping("/api/v1/salons/{salonId}")
    ResponseEntity<SalonDto> getSalon(@PathVariable("salonId") UUID salonId);

    // ══ platform power over a stylist. V025 (Session 51). ═════════════════════════════════════
    //
    // Two DIFFERENT powers, and the console must not blur them:
    //
    //   removeStylistFromSalon — employment. The same act an owner performs. They can work
    //                            elsewhere tomorrow.
    //   suspendStylist         — the platform barring them from BMP entirely.
    //
    // Collapsing them would mean either an admin cannot bar anyone, or a salon owner could bar
    // somebody from every OTHER salon by sacking them.

    /** Mirrors bmp-salon's InternalSalonController.StylistAdminView. Partial, as always. */
    record StylistAdminDto(
        UUID id, UUID userId, String name, String speciality,
        java.math.BigDecimal overallRating, int totalReviews,
        boolean suspended, java.time.Instant suspendedAt, String suspensionReason,
        UUID suspendedByStaffId, java.time.Instant reinstatedAt,
        int activeSalonCount, List<UUID> activeSalonIds) {}

    record SuspendStylistBody(String reason, UUID staffId) {}

    @GetMapping("/api/v1/salons/internal/stylists/{stylistId}")
    StylistAdminDto getStylist(@PathVariable("stylistId") UUID stylistId);

    @GetMapping("/api/v1/salons/internal/stylists/suspended")
    List<StylistAdminDto> suspendedStylists();

    /**
     * Bar a stylist from the platform.
     *
     * <p>Does NOT touch their existing salon links — that is deliberate on the bmp-salon side, so
     * a salon keeps an honest record of an employment that really happened. They simply stop
     * being bookable.
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/v1/salons/internal/stylists/{stylistId}/suspend")
    StylistAdminDto suspendStylist(@PathVariable("stylistId") UUID stylistId,
                                    @RequestBody SuspendStylistBody body);

    @org.springframework.web.bind.annotation.PostMapping("/api/v1/salons/internal/stylists/{stylistId}/reinstate")
    StylistAdminDto reinstateStylist(@PathVariable("stylistId") UUID stylistId);

    /** Employment, not a ban. Same effect as the owner's own remove. */
    @org.springframework.web.bind.annotation.PostMapping("/api/v1/salons/internal/salons/{salonId}/stylists/{stylistId}/remove")
    void removeStylistFromSalon(@PathVariable("salonId") UUID salonId,
                                 @PathVariable("stylistId") UUID stylistId,
                                 @RequestParam("staffId") UUID staffId);

    /**
     * The salon's stylists.
     *
     * <p>Used to answer the most common salon complaint — "we're not getting any bookings" —
     * which is almost always nobody being marked available rather than a lack of demand.
     */
    @GetMapping("/api/v1/salons/{salonId}/stylists")
    List<StylistSalonDto> listStylists(@PathVariable("salonId") UUID salonId);

    record StatusChangeRequest(
        String status,
        /**
         * The moderator's reason, forwarded to bmp-salon so the OWNER can be told it.
         * Session 46 — before this, a decision reached the salon with no explanation attached
         * and the owner was never notified at all.
         */
        String decisionNote,
        /**
         * The commission to apply, in basis points. Session 48.
         *
         * <p>Travels WITH the decision rather than as a second call on purpose: two calls means a
         * window where the salon is approved and live at a rate nobody agreed, and a failure
         * between them leaves the console convinced it set a rate it didn't. One request, one
         * transaction on the far side.
         *
         * <p>Null = don't touch it. See SalonDecisionRequest for why that isn't zero.
         */
        Integer commissionBps) {}

    /**
     * Apply a moderation decision.
     *
     * <p>THIS is what makes a salon visible to customers. The {@code salon_review} row in
     * admin_schema records the decision; this call enacts it. Without it, approving in the
     * console would change a row nobody outside the console reads, and the salon would stay
     * invisible — with the moderator convinced they had approved it.
     */
    @PutMapping("/api/v1/salons/internal/{salonId}/status")
    void setSalonStatus(@PathVariable("salonId") UUID salonId, @RequestBody StatusChangeRequest req);

    record SalonSupportSummary(
        UUID id, String name, String location, String status,
        int stylistCount, int activeStylistsToday, int stylistsWithHours, String configWarning
    ) {}

    /** "Why aren't we getting bookings?" — answered server-side, in one call. */
    @GetMapping("/api/v1/salons/internal/{salonId}/support-summary")
    SalonSupportSummary supportSummary(@PathVariable("salonId") UUID salonId);

    // ── Session 48: everything a moderator needs to actually judge a salon ─────────────────────

    record ModerationServiceItem(String name, long pricePaise, int durationMinutes, boolean archived) {}
    record ModerationPhoto(String url, String caption) {}

    /**
     * The full review packet. Mirrors bmp-salon's ModerationPacket field-for-field.
     *
     * <p>Feign maps by NAME — a rename on either side produces silent nulls rather than a compile
     * error, which on this record would mean a moderator approving a salon whose details had
     * quietly become blank. The contract guard in CI covers the pair.
     */
    record ModerationPacket(
        java.util.UUID salonId, String reference, String name, String status,
        String area, String pincode, String address, String about,
        String imageUrl, Double lat, Double lng, String mapLink,
        java.util.List<String> categories,
        java.util.UUID ownerUserId, String ownerName, String ownerEmail, String ownerPhone,
        int stylistCount,
        java.util.List<ModerationServiceItem> services,
        java.util.List<ModerationPhoto> photos,
        java.time.Instant createdAt) {}

    @GetMapping("/api/v1/salons/internal/{salonId}/moderation")
    ResponseEntity<ModerationPacket> moderationPacket(@PathVariable("salonId") java.util.UUID salonId);

    /**
     * Salons sitting at 'pending'. Used to find ones whose review row was never created — a failed
     * enqueue at signup would otherwise hide them from the queue permanently.
     */
    @GetMapping("/api/v1/salons/internal/pending-review")
    java.util.List<java.util.UUID> pendingReview();

    /** One row of the console's salon list. Mirrors bmp-salon's SalonAdminRow. */
    record SalonAdminRow(
        java.util.UUID salonId, String reference, String name, String status,
        String area, String pincode, String ownerName, String ownerPhone,
        java.time.Instant createdAt, java.time.Instant wentLiveAt) {}

    @GetMapping("/api/v1/salons/internal/all")
    java.util.List<SalonAdminRow> allSalons(@RequestParam(name = "status", required = false) String status);

    // ── moderation ────────────────────────────────────────────────────────────────────────────

    record RemovePhotoRequest(String reason) {}

    record PhotoRemoved(java.util.UUID photoId, java.util.UUID salonId) {}

    /**
     * Take a reported photo down. Session 60.
     *
     * <p>A real delete, unlike a review — nothing references a photo row, so there is nothing to
     * orphan and nothing anybody will restore. The asymmetry with review moderation is deliberate;
     * see {@code InternalSalonController.removePhoto} for why.
     *
     * <p>404 means it is already gone, which the caller treats as success.
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/v1/salons/internal/photos/{photoId}/remove")
    PhotoRemoved removePhoto(@org.springframework.web.bind.annotation.PathVariable("photoId") java.util.UUID photoId,
                              @org.springframework.web.bind.annotation.RequestBody RemovePhotoRequest body);

    // ── profile editing by console staff. Session 65. ─────────────────────────────────────────

    /**
     * Null means UNCHANGED, on every field.
     *
     * <p>Only the fields the caller is permitted to change are ever populated — SalonEditScope
     * decides which those are, before this record is built. Deliberately narrower than bmp-salon's
     * own {@code UpdateSalonRequest}: no {@code status} (moderation owns it), no {@code location}
     * (nobody in an office should move a shop's map pin), no image fields.
     */
    record EditSalonProfileRequest(
            String name, String area, String pincode, String address, String about,
            java.util.List<String> categories,
            String bookingNotifyEmail, String bookingNotifyPhone) {}

    /**
     * Edit a salon's profile on behalf of a staff member.
     *
     * <p>The authority check happens HERE, in bmp-admin, because only this service can see who the
     * staff member is. bmp-salon holds the invariant that {@code status} can never be set through a
     * profile edit, whoever asks.
     */
    /*
     * Returns VOID, deliberately.
     *
     * bmp-salon answers with its own SalonResponse — `id`, `location`, `wentLiveAt` and a dozen
     * other fields under names that do not match SalonAdminRow (`salonId` vs `id`, for a start).
     * Declaring SalonAdminRow here would compile, deserialize by NAME, and hand back a record whose
     * every mismatched field is silently null. That is the exact failure mode that lost a session
     * to bmp-auth's CreateUserRequest, and it is invisible until somebody reads the nulls.
     *
     * Ignoring the body avoids inventing a third copy of the salon shape. The console refetches the
     * row it already knows how to parse.
     */
    @org.springframework.web.bind.annotation.PatchMapping("/api/v1/salons/internal/{salonId}/profile")
    void editProfile(@org.springframework.web.bind.annotation.PathVariable("salonId") java.util.UUID salonId,
                      @org.springframework.web.bind.annotation.RequestBody EditSalonProfileRequest body);
}
