package com.bmp.salon.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Tells bmp-admin that a salon needs reviewing.
 *
 * <p>Called when a salon is created. Salons are created {@code pending} and are invisible to
 * customers until a human approves them — so if this call never happens, the salon is stranded:
 * not in anyone's queue, not visible to anyone, and indistinguishable (to the owner) from being
 * ignored.
 *
 * <p><b>Why a direct call rather than an outbox event.</b> The outbox → Kafka path in bmp-common
 * is the right mechanism for things that must eventually happen, and moderation arguably
 * qualifies. But it adds a consumer, a topic and a failure mode to a queue that gets checked by
 * a human anyway — and the endpoint is idempotent, so a missed call is recoverable by re-posting
 * it. If the queue ever needs stronger delivery guarantees than "a person notices", this is the
 * first thing to move onto the outbox.
 */
@FeignClient(name = "bmp-admin-service", configuration = com.bmp.salon.config.FeignInternalKeyConfig.class)
public interface AdminServiceClient {

    /** Idempotent — a repeated call returns the existing PENDING review rather than a second. */
    @PostMapping("/api/v1/admin/salons/{salonId}/enqueue-review")
    void enqueueSalonReview(@PathVariable("salonId") UUID salonId);

    /**
     * The salon's latest moderation review — status, and on rejection the moderator's reason.
     * Session 46.
     *
     * <p>Mirrors bmp-admin's {@code SalonReviewResponse}. Duplicated rather than shared for the
     * same reason as everywhere else in this codebase: putting it in bmp-common would couple all
     * thirteen services to a schema two of them use.
     *
     * <p><b>Returns 204 when the salon has never been submitted</b>, which Feign surfaces as a
     * null body rather than an exception. Callers must handle that — it is the state of any salon
     * created before the queue existed, or whose enqueue call failed.
     */
    @GetMapping("/api/v1/admin/salons/{salonId}/review")
    ResponseEntity<SalonReviewDto> latestReview(@PathVariable("salonId") UUID salonId);

    /** Put a rejected salon back in the queue after the owner has fixed what was wrong. */
    @PostMapping("/api/v1/admin/salons/{salonId}/resubmit")
    SalonReviewDto resubmit(@PathVariable("salonId") UUID salonId, @RequestBody ResubmitRequest req);

    record ResubmitRequest(String note) {}

    /**
     * @param decisionNote the moderator's reason. On a rejection this is what the OWNER reads,
     *                     and it is the only thing telling them what to fix.
     */
    record SalonReviewDto(
            UUID id, UUID salonId, String salonName, String ownerName, String area,
            String status, java.time.Instant submittedAt, java.time.Instant decidedAt,
            String decidedByName, String decisionNote,
            java.util.Map<String, Boolean> checks,
            int submissionCount, String resubmissionNote) {}
}
