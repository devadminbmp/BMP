package com.bmp.review.controllers;

import com.bmp.review.entities.Review;
import com.bmp.review.repositories.ReviewRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Take a review off the site, or put it back. {@code ROLE_SERVICE} only — called by bmp-admin.
 * Session 60.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE TODO THIS CLOSES
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code ConsoleController.resolveContentReport} carried, honestly, for several sessions:
 *
 * <blockquote>upholding should also hide the content. Recording the decision without acting on it
 * means a moderator marks something as removed and it stays visible.</blockquote>
 *
 * <p>That is the worst shape a moderation tool can take. The moderator does the work, the audit log
 * says the content was removed, the reporter is told it was handled — and the abusive review is
 * still on the salon's page. The failure is invisible from inside the console, and the second
 * report looks like a duplicate of one already resolved.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS LIVES IN bmp-review, NOT bmp-admin
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * The row belongs to bmp-review, and so does every read that has to start excluding it — the salon
 * page, the stylist page, the average, the histogram. A moderation action that reached across the
 * service boundary and wrote {@code hidden_at} directly would leave those four reads to be
 * remembered by whoever wrote the update. They are remembered here instead, in the repository that
 * owns them.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * NOT DELETE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Moderation is reversible, {@code booking_id} is unique so a delete would let the same customer
 * write a replacement, and the rating is history. See V006.
 */
@Tag(name = "Internal review moderation",
     description = "Service-only. Hide a review that moderation upheld a report against, or restore one.")
@RestController
@RequestMapping("/api/v1/reviews/internal")
@PreAuthorize("hasRole('SERVICE')")
public class InternalReviewModerationController {

    private static final Logger log = LoggerFactory.getLogger(InternalReviewModerationController.class);

    private final ReviewRepository reviews;

    public InternalReviewModerationController(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    /**
     * @param reason  shown to nobody publicly, but it is the answer to "why was this taken down",
     *                which is the first question an appeal asks. Required by V006's CHECK.
     * @param staffId the moderator. Also required — "somebody removed it" is not an answer.
     */
    public record HideRequest(
            @NotBlank @Size(max = 500) String reason,
            UUID staffId) {}

    public record ModerationStatus(UUID reviewId, boolean hidden, Instant hiddenAt,
                                    String hiddenReason, UUID hiddenByStaffId) {

        static ModerationStatus of(Review r) {
            return new ModerationStatus(r.getId(), r.isHidden(), r.getHiddenAt(),
                    r.getHiddenReason(), r.getHiddenByStaffId());
        }
    }

    /**
     * Hide it.
     *
     * <p><b>Idempotent</b>, and that matters more than it looks: a moderator double-clicking, or a
     * report upheld twice by two people working the same queue, must not overwrite the original
     * reason and moderator with a later one. The first decision is the one an appeal is against.
     *
     * <p>Returns 200 with the CURRENT state rather than 409 on an already-hidden review. The caller
     * (bmp-admin) is trying to make the content invisible; it already is, so the call succeeded in
     * every sense the caller cares about, and a conflict would make the console show an error for
     * an outcome that is exactly what was wanted.
     */
    @Operation(summary = "Hide a review after a report was upheld",
               description = "Idempotent. The row is kept — moderation is reversible and booking_id is unique.")
    @PostMapping("/{reviewId}/hide")
    @Transactional
    public ModerationStatus hide(@PathVariable UUID reviewId, @RequestBody HideRequest req) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        if (review.isHidden()) {
            log.info("Review {} was already hidden — leaving the original reason and moderator in "
                    + "place. The first decision is the one any appeal is against.", reviewId);
            return ModerationStatus.of(review);
        }

        review.hide(req.reason(), req.staffId());
        reviews.save(review);

        log.info("Review {} on salon {} hidden by staff {} — {}",
                reviewId, review.getSalonId(), req.staffId(), req.reason());
        return ModerationStatus.of(review);
    }

    /**
     * Put it back — an appeal succeeded, or the report was upheld in error.
     *
     * <p>Exists because a hide with no undo is a delete with extra steps, and a moderator who knows
     * there is no undo hesitates over exactly the borderline cases that most need a decision.
     */
    @Operation(summary = "Restore a hidden review")
    @PostMapping("/{reviewId}/restore")
    @Transactional
    public ModerationStatus restore(@PathVariable UUID reviewId) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
        review.unhide();
        reviews.save(review);
        log.info("Review {} restored — it is visible again on salon {}.", reviewId, review.getSalonId());
        return ModerationStatus.of(review);
    }

    /**
     * Is this one hidden, and why?
     *
     * <p>So the console can show a moderator what already happened to a piece of content before
     * they act on a second report about it — without which two people work the same item and the
     * audit log grows a decision that changed nothing.
     */
    @Operation(summary = "Current moderation state of a review")
    @GetMapping("/{reviewId}/moderation")
    public ModerationStatus status(@PathVariable UUID reviewId) {
        return reviews.findById(reviewId).map(ModerationStatus::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));
    }

    // ── data export ───────────────────────────────────────────────────────────────────────────

    /**
     * One review, as it appears in a data-subject export.
     *
     * @param hidden included on purpose. A review we removed is still data we hold about the
     *               person, and it is the item they are least likely to know about — omitting it
     *               would make the export a curated subset rather than a disclosure.
     */
    public record ExportedReview(
            UUID id, UUID bookingId, UUID salonId, UUID stylistId,
            int salonRating, Integer stylistRating, String reviewText,
            boolean hidden, Instant createdAt, Instant updatedAt) {}

    /**
     * Everything this person wrote. Service-only, for the DPDP/GDPR export in bmp-admin.
     *
     * <p>Returns an empty list rather than 404 for somebody who never reviewed anything — "you hold
     * nothing of mine here" is a valid and useful answer to a subject access request, and a 404
     * would make the assembling service treat a complete answer as a failed call.
     */
    @Operation(summary = "Every review by one author, for a data export",
               description = "SERVICE only. Includes moderated (hidden) reviews — they are still data we hold.")
    @GetMapping("/by-author/{userId}")
    public java.util.List<ExportedReview> byAuthor(@PathVariable UUID userId) {
        return reviews.findByAuthorUserIdOrderByCreatedAtDesc(userId).stream()
                .map(r -> new ExportedReview(r.getId(), r.getBookingId(), r.getSalonId(),
                        r.getStylistId(), r.getSalonRating(), r.getStylistRating(),
                        r.getReviewText(), r.isHidden(), r.getCreatedAt(), r.getUpdatedAt()))
                .toList();
    }
}
