package com.bmp.admin.controllers;

import com.bmp.admin.entities.ContentReport;
import com.bmp.admin.repositories.ContentReportRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Somebody reported something. {@code ROLE_SERVICE} only — called by bmp-user. Session 61.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE QUEUE HAD NO INPUT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code content_report} has existed since Session 23, the console has a moderation screen, and
 * Session 60 wired upholding so it genuinely hides a review or deletes a photo. And the
 * {@link ContentReport} constructor was never called from anywhere: no endpoint created a row, and
 * no app had a "Report" control.
 *
 * <p>So the whole pipeline was unreachable from BOTH ends at once — which is exactly why nobody
 * noticed. A feature missing its input looks identical to a feature nobody has used yet, and the
 * empty queue was read as "no bad content" rather than "no way to tell us about any".
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE REPORTER IS NOT IN THE REQUEST BODY
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * bmp-user takes it from the caller's token and passes it here. Same reasoning as support tickets:
 * a field a caller can set is a field a caller can forge, and a report attributed to somebody else
 * is both a false accusation and a way to get a rival's reports dismissed as spam.
 */
@Tag(name = "Internal content reports",
     description = "Service-only. Receives a report raised by a customer through bmp-user.")
@RestController
@RequestMapping("/api/v1/content-reports")
@PreAuthorize("hasRole('SERVICE')")
public class InternalContentReportController {

    private static final Logger log = LoggerFactory.getLogger(InternalContentReportController.class);

    /**
     * What can be reported. An allow-list, because {@code content_type} decides what
     * {@code enactModeration} does when a report is upheld — an unknown type there produces a
     * decision that changes nothing, logged as an error nobody reads.
     */
    private static final Set<String> REPORTABLE =
            Set.of("review", "salon_photo", "salon_profile", "stylist_profile");

    /**
     * Why. A fixed list rather than free text, for two reasons that both matter:
     *
     * <ul>
     *   <li>A moderator triaging fifty items needs to sort them. "Spam" and "this is spam!!" are
     *       the same report and sort differently.</li>
     *   <li>{@code safety} is the one that has to jump the queue. That is only possible if the
     *       reason is a value the code can compare, not a sentence.</li>
     * </ul>
     */
    private static final Set<String> REASONS =
            Set.of("spam", "offensive", "misleading", "not_mine", "safety", "other");

    /** Reports that mean somebody may be at risk. Everything else can wait its turn. */
    private static final Set<String> URGENT = Set.of("safety");

    private final ContentReportRepository reports;

    public InternalContentReportController(ContentReportRepository reports) {
        this.reports = reports;
    }

    /**
     * @param reportedByUserId taken from the token by bmp-user, never from the browser.
     * @param salonId          which business this concerns, so a moderator can see three reports
     *                         about one salon as a pattern rather than three unrelated items.
     *                         Nullable: a stylist profile is not owned by a salon.
     */
    public record RaiseReport(
            @NotBlank @Size(max = 40) String contentType,
            @NotNull UUID contentId,
            UUID salonId,
            @NotNull UUID reportedByUserId,
            @NotBlank @Size(max = 60) String reason,
            @Size(max = 1000) String detail) {}

    public record ReportAccepted(UUID id, String status, boolean alreadyReportedByYou,
                                  Instant createdAt) {}

    /**
     * Record it.
     *
     * <h2>One open report per person per item</h2>
     * A second report of the same content by the same person returns the FIRST one rather than
     * creating a duplicate — and says so, so the app can tell them "you've already reported this"
     * instead of silently thanking them again. Without this, somebody who taps twice, or who
     * reports the same review from two screens, inflates the queue with items a moderator has to
     * read and dismiss individually.
     *
     * <p>Two DIFFERENT people reporting the same thing are two rows on purpose: how many people
     * reported something is the single most useful triage signal there is, and de-duplicating
     * across users would throw it away.
     */
    @Operation(summary = "Raise a content report",
               description = "Idempotent per reporter per item. Returns the existing report if they already raised one.")
    @PostMapping
    @Transactional
    public ReportAccepted raise(@Valid @RequestBody RaiseReport req) {
        if (!REPORTABLE.contains(req.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "UNKNOWN_CONTENT_TYPE: one of " + REPORTABLE);
        }
        if (!REASONS.contains(req.reason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "UNKNOWN_REASON: one of " + REASONS);
        }

        List<ContentReport> existing = reports.findByContentIdAndReportedByUserId(
                req.contentId(), req.reportedByUserId());
        for (ContentReport r : existing) {
            if ("open".equals(r.getStatus())) {
                log.debug("User {} has already reported {} — returning the open report.",
                        req.reportedByUserId(), req.contentId());
                return new ReportAccepted(r.getId(), r.getStatus(), true, r.getCreatedAt());
            }
        }

        ContentReport saved = reports.save(new ContentReport(
                req.contentType(), req.contentId(), req.salonId(), req.reportedByUserId(),
                req.reason(), req.detail() == null ? null : req.detail().trim()));

        /*
         * Safety reports are logged at WARN, deliberately louder than the rest.
         *
         * The moderation queue is worked oldest-first, which is right for spam and wrong for a
         * report that says somebody is at risk. Until the console sorts on urgency, a log line an
         * on-call engineer's alerting can match is the honest interim — and saying that here is
         * better than a comment claiming the queue prioritises something it does not.
         */
        if (URGENT.contains(req.reason())) {
            log.warn("SAFETY report {} raised against {} {} — this should be looked at before the "
                    + "rest of the queue. The queue itself is still oldest-first.",
                    saved.getId(), req.contentType(), req.contentId());
        } else {
            log.info("Content report {} raised against {} {} ({}).",
                    saved.getId(), req.contentType(), req.contentId(), req.reason());
        }

        return new ReportAccepted(saved.getId(), saved.getStatus(), false, saved.getCreatedAt());
    }

    /**
     * How many people have reported this, and has it been dealt with.
     *
     * <p>For the app to show "you already reported this" rather than offering the button again —
     * which reads as the first report having been ignored.
     */
    @Operation(summary = "Report state for one piece of content")
    @GetMapping("/for/{contentId}")
    public ReportSummary forContent(@PathVariable UUID contentId,
                                     @RequestParam(required = false) UUID byUserId) {
        List<ContentReport> all = reports.findByContentIdOrderByCreatedAtDesc(contentId);
        boolean mine = byUserId != null && all.stream()
                .anyMatch(r -> byUserId.equals(r.getReportedByUserId()) && "open".equals(r.getStatus()));
        long open = all.stream().filter(r -> "open".equals(r.getStatus())).count();
        return new ReportSummary(contentId, (int) open, mine);
    }

    public record ReportSummary(UUID contentId, int openReports, boolean reportedByYou) {}
}
