package com.bmp.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.UUID;

/**
 * Reporting content to the moderation queue, which lives in bmp-admin. Session 61.
 *
 * <p>Same shape as {@code SupportServiceClient}: bmp-user owns the customer-facing door and the
 * identity that comes with the token; bmp-admin owns the queue and the decision. The reporter's id
 * is filled in by the controller from the caller's token and never accepted from the request.
 */
@FeignClient(name = "bmp-admin-service",
             contextId = "moderationServiceClient",
             configuration = com.bmp.user.config.FeignInternalKeyConfig.class)
public interface ModerationServiceClient {

    record RaiseReport(String contentType, UUID contentId, UUID salonId,
                        UUID reportedByUserId, String reason, String detail) {}

    /** @param alreadyReportedByYou true when this person had already reported the same item. */
    record ReportAccepted(UUID id, String status, boolean alreadyReportedByYou, Instant createdAt) {}

    record ReportSummary(UUID contentId, int openReports, boolean reportedByYou) {}

    @PostMapping("/api/v1/content-reports")
    ReportAccepted raise(@RequestBody RaiseReport body);

    @GetMapping("/api/v1/content-reports/for/{contentId}")
    ReportSummary forContent(@PathVariable("contentId") UUID contentId,
                              @RequestParam(name = "byUserId", required = false) UUID byUserId);
}
