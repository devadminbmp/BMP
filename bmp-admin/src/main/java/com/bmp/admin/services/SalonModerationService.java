package com.bmp.admin.services;

import com.bmp.admin.client.SalonServiceClient;
import com.bmp.admin.client.UserServiceClient;
import com.bmp.admin.dto.ConsoleDtos.*;
import com.bmp.admin.entities.SalonReview;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.SalonReviewRepository;
import com.bmp.admin.security.StaffPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The gate on the marketplace.
 *
 * <p>A salon that signs up is not visible to customers until somebody confirms it's a real
 * business at a real address. Without this, "list your salon" is an open door to putting
 * anything in front of your customers, under your brand.
 *
 * <h2>Two rules the service enforces rather than trusting the UI</h2>
 * <ul>
 *   <li><b>Rejection requires a note.</b> "No" without a reason produces a support ticket every
 *       single time, and the owner has no idea what to fix.</li>
 *   <li><b>A decided review can't be silently re-decided.</b> Moving something out of a terminal
 *       state has to be deliberate (suspension), so an approval can't quietly become a rejection
 *       with no trace of the first decision.</li>
 * </ul>
 */
@Service
public class SalonModerationService {

    private static final Logger log = LoggerFactory.getLogger(SalonModerationService.class);

    private final SalonReviewRepository reviews;
    private final BmpStaffRepository staffRepo;
    private final SalonServiceClient salons;
    private final UserServiceClient users;
    private final AuditLogService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    public SalonModerationService(SalonReviewRepository reviews, BmpStaffRepository staffRepo,
                                  SalonServiceClient salons, UserServiceClient users,
                                  AuditLogService audit) {
        this.reviews = reviews;
        this.staffRepo = staffRepo;
        this.salons = salons;
        this.users = users;
        this.audit = audit;
    }

    public List<SalonReviewResponse> list(String status) {
        List<SalonReview> found = status == null || status.isBlank()
                ? reviews.findAllByOrderBySubmittedAtDesc()
                : reviews.findByStatusOrderBySubmittedAtAsc(status);
        return found.stream().map(this::toResponse).toList();
    }

    /**
     * Called when a salon is created, to put it in the queue.
     *
     * <p>Idempotent: a repeated call for the same salon returns the existing review rather than
     * creating a second one, because whatever triggers this (an event, a retry, a manual
     * backfill) will eventually fire twice.
     */
    @Transactional
    public SalonReviewResponse enqueue(UUID salonId) {
        return reviews.findBySalonId(salonId)
                .map(this::toResponse)
                .orElseGet(() -> toResponse(reviews.save(new SalonReview(salonId))));
    }

    @Transactional
    public SalonReviewResponse decide(UUID reviewId, SalonDecisionRequest req, StaffPrincipal caller, String ip) {
        SalonReview review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND"));

        // The rule that saves a support ticket every time.
        if ("rejected".equals(req.decision()) && (req.note() == null || req.note().trim().length() < 10)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A rejection needs a note explaining what the owner should fix.");
        }
        // An already-decided review can only move to 'suspended' — which is a deliberate act,
        // not a correction. Re-approving a rejection would erase the fact it was ever rejected.
        if (!"pending".equals(review.getStatus()) && !"suspended".equals(req.decision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This salon has already been " + review.getStatus() + ". Only suspension is available now.");
        }

        String checksJson;
        try {
            checksJson = mapper.writeValueAsString(req.checks() == null ? Map.of() : req.checks());
        } catch (Exception e) {
            checksJson = "{}";
        }

        // Enact the decision in bmp-salon FIRST. If this fails, nothing is recorded as decided —
        // a review row saying "approved" beside a salon that is still invisible is the worst of
        // both worlds, because the moderator walks away believing the job is done.
        try {
            salons.setSalonStatus(review.getSalonId(),
                    new SalonServiceClient.StatusChangeRequest(req.decision()));
        } catch (Exception e) {
            log.error("Could not apply '{}' to salon {} ({}) — the decision was NOT recorded.",
                    req.decision(), review.getSalonId(), e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not update the salon. Nothing was changed — please try again.");
        }

        review.decide(req.decision(), caller.staffId(), req.note(), checksJson);

        audit.record("bmp_staff", caller.staffId(), "SALON_" + req.decision().toUpperCase(),
                "salon", review.getSalonId(),
                Map.of("reviewId", review.getId().toString(), "checks", req.checks() == null ? Map.of() : req.checks()),
                ip, caller.email(), caller.role(), req.note());

        log.info("Salon {} {} by {}", review.getSalonId(), req.decision(), caller.email());

        // TODO(notification): tell the owner. A rejection the owner never hears about is
        // indistinguishable from being ignored, and they will chase support instead.
        return toResponse(review);
    }

    public long pendingCount() {
        return reviews.countByStatus("pending");
    }

    @SuppressWarnings("unchecked")
    private SalonReviewResponse toResponse(SalonReview r) {
        // Enrichment is best-effort per row. If bmp-salon is unreachable the queue still
        // renders with ids rather than failing entirely — a moderator seeing a degraded list
        // during a partial outage is far better than an error page.
        String salonName = null;
        String area = null;
        try {
            SalonServiceClient.SalonDto salon = salons.getSalon(r.getSalonId()).getBody();
            if (salon != null) salonName = salon.name();
        } catch (Exception e) {
            log.warn("Could not enrich salon {} for moderation queue ({})", r.getSalonId(), e.toString());
        }

        String decidedByName = r.getDecidedBy() == null ? null
                : staffRepo.findById(r.getDecidedBy()).map(s -> s.getName()).orElse(null);

        Map<String, Boolean> checks = Map.of();
        try {
            if (r.getChecks() != null) checks = mapper.readValue(r.getChecks(), Map.class);
        } catch (Exception ignored) {
            // A malformed checks blob is cosmetic; don't fail the row for it.
        }

        return new SalonReviewResponse(
                r.getId(), r.getSalonId(), salonName, null, area,
                r.getStatus(), r.getSubmittedAt(), r.getDecidedAt(), decidedByName,
                r.getDecisionNote(), checks);
    }
}
