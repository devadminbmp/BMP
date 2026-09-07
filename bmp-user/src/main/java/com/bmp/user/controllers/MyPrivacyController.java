package com.bmp.user.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.user.client.DataRequestServiceClient;
import com.bmp.user.client.ModerationServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The two things a customer can do about their own data and about content they've seen.
 * Session 61.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THESE TWO SIT TOGETHER
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * "Report this" and "give me my data" look unrelated until you notice they are the only two places
 * where a customer exercises a RIGHT rather than uses the product — and both were reachable only
 * from the staff side. The moderation queue had no input at all, and the data-request queue could
 * only be filled by an agent typing on somebody's behalf.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS CONTROLLER REFUSES TO ACCEPT FROM THE CALLER
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Identity. Every downstream call fills the user id from the token. It is the same rule as
 * {@code SupportController}, and it matters more here than there:
 *
 * <ul>
 *   <li>A report attributed to somebody else is a false accusation, and a way to get a rival's
 *       genuine reports written off as spam.</li>
 *   <li>A deletion request accepted with a caller-supplied user id is an endpoint for erasing
 *       other people's accounts.</li>
 * </ul>
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * NOTHING HERE ACTS IMMEDIATELY
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * A report opens a queue item; a data request opens a queue item. Neither hides content nor
 * deletes an account on the spot, and the responses say so in words the app shows verbatim.
 *
 * <p>For deletion that is not caution, it is the design: erasure is irreversible and identity is
 * verified by a human first, because the commonest reason for a deletion request nobody made is
 * that the account has been taken over. A one-tap "delete everything" would be a weapon for
 * whoever stole the phone.
 */
@Tag(name = "My privacy and reporting",
     description = "Report content, and ask for a copy or deletion of your data. Your identity comes from your login.")
@RestController
@RequestMapping("/api/v1/me")
public class MyPrivacyController {

    private static final Logger log = LoggerFactory.getLogger(MyPrivacyController.class);

    private final ModerationServiceClient moderation;
    private final DataRequestServiceClient dataRequests;

    public MyPrivacyController(ModerationServiceClient moderation,
                                DataRequestServiceClient dataRequests) {
        this.moderation = moderation;
        this.dataRequests = dataRequests;
    }

    // ── reporting ─────────────────────────────────────────────────────────────────────────────

    /**
     * @param salonId which business the content belongs to. Sent by the app because it already has
     *                it on screen, and it is a HINT for grouping, not an authorisation input —
     *                nothing downstream trusts it to decide anything.
     */
    public record ReportRequest(
            @NotBlank @Size(max = 40) String contentType,
            @NotNull UUID contentId,
            UUID salonId,
            @NotBlank @Size(max = 60) String reason,
            @Size(max = 1000) String detail) {}

    /**
     * Report a review, a photo, or a profile.
     *
     * <h2>Returns 200 with {@code alreadyReported}, not 409</h2>
     * Reporting the same thing twice is not an error the person should be shown. They may have
     * tapped twice, or come back a week later having forgotten. Telling them "you've already
     * reported this, we're looking at it" is the true and reassuring answer; an error implies the
     * first report failed.
     */
    @Operation(summary = "Report content",
               description = "Opens a moderation item. Nothing is hidden immediately — a person reviews it.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/reports")
    public ResponseEntity<ReportResult> report(@AuthenticationPrincipal AuthenticatedUser me,
                                                @Valid @RequestBody ReportRequest req) {
        ModerationServiceClient.ReportAccepted accepted;
        try {
            accepted = moderation.raise(new ModerationServiceClient.RaiseReport(
                    req.contentType(), req.contentId(), req.salonId(),
                    me.userId(),          // from the token, never the body
                    req.reason(),
                    req.detail() == null ? null : req.detail().trim()));
        } catch (Exception e) {
            log.error("Could not record a content report from {} against {} ({})",
                    me.userId(), req.contentId(), e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "We couldn't record that just now. Nothing was sent — please try again.");
        }

        log.info("Content report {} raised by {} against {} {}",
                accepted.id(), me.userId(), req.contentType(), req.contentId());

        return ResponseEntity.status(accepted.alreadyReportedByYou() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new ReportResult(
                        accepted.alreadyReportedByYou(),
                        accepted.alreadyReportedByYou()
                                ? "You've already reported this. Someone is looking at it."
                                : "Thanks — someone will review this. We'll act on it if it breaks our rules."));
    }

    public record ReportResult(boolean alreadyReported, String message) {}

    /** Whether this person has an open report on an item, so the app can say so instead of re-offering. */
    @Operation(summary = "Have I reported this?")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/reports/for/{contentId}")
    public ModerationServiceClient.ReportSummary reportedByMe(
            @AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID contentId) {
        try {
            return moderation.forContent(contentId, me.userId());
        } catch (Exception e) {
            /*
             * A failure here must not break the screen the button sits on. "I don't know whether you
             * reported it" degrades to offering the button again, and a duplicate report is
             * de-duplicated server-side anyway — so the worst case is harmless.
             */
            log.debug("Could not read report state for {} ({})", contentId, e.toString());
            return new ModerationServiceClient.ReportSummary(contentId, 0, false);
        }
    }

    // ── my data ───────────────────────────────────────────────────────────────────────────────

    /** @param requestType {@code export} or {@code delete}. */
    public record DataRequestBody(@NotBlank @Size(max = 20) String requestType) {}

    /**
     * Ask for a copy of your data, or for your account to be erased.
     *
     * <h2>Deletion does not happen here, and the response says so</h2>
     * It opens a request that a person verifies before acting on. The message returned is written
     * for the customer and shown verbatim, because "your account will be deleted" and "we've
     * received your request to delete your account" are very different promises and only one of
     * them is true at this point.
     *
     * <h2>The email is taken from the account</h2>
     * Not from the request. It is what the staff console shows as the subject's contact and what an
     * agent verifies against — accepting one from the caller would let somebody open a request
     * against their own account and have the answer sent somewhere else.
     */
    @Operation(summary = "Ask for a copy of your data, or to be deleted",
               description = "Opens a request. A person verifies who you are before anything happens — erasure cannot be undone.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/data-requests")
    public ResponseEntity<DataRequestResult> requestMyData(
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody DataRequestBody req) {

        String type = req.requestType().trim().toLowerCase();
        if (!("export".equals(type) || "delete".equals(type))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "requestType must be 'export' or 'delete'.");
        }

        DataRequestServiceClient.Raised raised;
        try {
            raised = dataRequests.raise(new DataRequestServiceClient.RaiseDataRequest(
                    type, me.userId(), null));
        } catch (Exception e) {
            log.error("Could not open a {} data request for {} ({})", type, me.userId(), e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "We couldn't open that request just now. Nothing was submitted — please try "
                    + "again, or contact support.");
        }

        log.info("Data request {} ({}) opened by the account holder {}", raised.id(), type, me.userId());

        return ResponseEntity.status(HttpStatus.CREATED).body(new DataRequestResult(
                raised.id(), type, raised.dueAt(),
                "export".equals(type)
                        ? "We've got it. We'll confirm it's you, put your data together and send it "
                          + "to you — by " + raised.dueAt() + " at the latest."
                        : "We've got it. Someone will confirm it's you before anything is deleted, "
                          + "because this can't be undone. Your bookings and invoices stay as "
                          + "records, without your personal details on them."));
    }

    public record DataRequestResult(UUID id, String requestType, String dueAt, String message) {}
}
