package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.Instant;
import java.util.UUID;

/**
 * Moderation actions against reviews. Session 60.
 *
 * <p>The console's job on a content report is to record a judgement AND make it true. Before this
 * client existed it only did the first half, which is the failure documented at length in
 * {@code InternalReviewModerationController}: the audit log said the content was removed and the
 * review was still on the salon's page.
 *
 * <p>Read-only lookups of reviews are deliberately NOT here. The console has no review browser and
 * does not need one — a moderator arrives from a report, which already carries the id.
 */
@FeignClient(name = "bmp-review-service",
             configuration = com.bmp.admin.config.FeignInternalKeyConfig.class)
public interface ReviewServiceClient {

    /** @param staffId the moderator — V006's CHECK requires it alongside the reason. */
    record HideRequest(String reason, UUID staffId) {}

    record ModerationStatus(UUID reviewId, boolean hidden, Instant hiddenAt,
                             String hiddenReason, UUID hiddenByStaffId) {}

    /** Idempotent on bmp-review's side: hiding an already-hidden review keeps the first decision. */
    @PostMapping("/api/v1/reviews/internal/{reviewId}/hide")
    ModerationStatus hide(@PathVariable("reviewId") UUID reviewId, @RequestBody HideRequest body);

    @PostMapping("/api/v1/reviews/internal/{reviewId}/restore")
    ModerationStatus restore(@PathVariable("reviewId") UUID reviewId);

    @GetMapping("/api/v1/reviews/internal/{reviewId}/moderation")
    ModerationStatus moderationStatus(@PathVariable("reviewId") UUID reviewId);

    /**
     * @param hidden a moderated review is still data we hold about the person, and the item they
     *               are least likely to know about — so the export declares it rather than
     *               quietly filtering it out.
     */
    record ExportedReview(UUID id, UUID bookingId, UUID salonId, UUID stylistId,
                           int salonRating, Integer stylistRating, String reviewText,
                           boolean hidden, Instant createdAt, Instant updatedAt) {}

    /** Everything one person wrote, for the DPDP export. Empty list, never 404, when they wrote none. */
    @GetMapping("/api/v1/reviews/internal/by-author/{userId}")
    java.util.List<ExportedReview> byAuthor(@PathVariable("userId") UUID userId);
}
