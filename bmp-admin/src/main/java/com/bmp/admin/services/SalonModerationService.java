package com.bmp.admin.services;

import com.bmp.admin.client.SalonServiceClient;
import com.bmp.admin.client.UserServiceClient;
import com.bmp.admin.dto.ConsoleDtos.*;
import com.bmp.admin.entities.SalonReview;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.SalonReviewRepository;
import com.bmp.admin.security.StaffPermission;
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

    /**
     * The queue: ONE ROW PER SALON, showing its CURRENT state. Session 65.
     *
     * <h2>The bug this fixes</h2>
     * This used to return every review ROW. A resubmission deliberately creates a new row rather
     * than reopening the rejected one (see {@link #resubmit} — reusing it would erase the fact it
     * was ever rejected), so a salon that was rejected once and resubmitted had TWO rows and
     * appeared TWICE in the console.
     *
     * <p>Reported as "by a single phone number we have different salon requests". Nothing was
     * duplicated: one owner, one phone, one salon, one constraint holding all of that — the queue
     * was simply showing a HISTORY where a moderator reads a WORK LIST. Two entries for one shop,
     * with no way to tell which one to act on.
     *
     * <h2>Latest per salon, then filter — and that order matters</h2>
     * Filtering first and collapsing second would answer a different question: "salons that have
     * EVER been rejected". A moderator opening the Rejected tab wants the ones sitting rejected
     * RIGHT NOW, waiting on the owner — not one that was rejected in March and has been live since
     * April. Collapsing first makes the status mean "current state", which is what the tab labels
     * promise.
     *
     * <p>Nothing is hidden: {@code submissionCount} on each row says how many times this salon has
     * been submitted, and the detail view carries the full submission history. The audit log holds
     * every decision independently of this.
     */
    public List<SalonReviewResponse> list(String status) {
        // Session 48 — before listing, catch anything that fell through the enqueue.
        reconcileMissingReviews();

        List<SalonReview> latestPerSalon = reviews.findAllByOrderBySubmittedAtDesc().stream()
                /*
                 * Newest first, so the FIRST row seen for a salon is its current one. `merge` with
                 * a keep-the-existing function makes that explicit rather than relying on a
                 * collector's undocumented preference between duplicates.
                 */
                .collect(java.util.stream.Collectors.toMap(
                        SalonReview::getSalonId,
                        r -> r,
                        (newest, older) -> newest,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .filter(r -> status == null || status.isBlank() || status.equalsIgnoreCase(r.getStatus()))
                .toList();

        /*
         * Pending oldest-first; everything else newest-first.
         *
         * A queue is worked from the oldest, because the person who has waited longest should not
         * keep being overtaken. A history is read newest-first. Same list, two jobs, and the sort
         * is what tells them apart.
         */
        List<SalonReview> ordered = new java.util.ArrayList<>(latestPerSalon);
        boolean pendingQueue = "pending".equalsIgnoreCase(status);
        ordered.sort(pendingQueue
                ? java.util.Comparator.comparing(SalonReview::getSubmittedAt)
                : java.util.Comparator.comparing(SalonReview::getSubmittedAt).reversed());

        return ordered.stream().map(this::toResponse).toList();
    }

    /**
     * Enqueue any salon that is 'pending' in bmp-salon but has no review row here. Session 48.
     *
     * <h2>The bug this closes</h2>
     * SalonService.create() calls bmp-admin to enqueue a review, and that call is deliberately
     * best-effort — a moderation queue being down must never block somebody's signup. It logs the
     * failure and carries on.
     *
     * <p>But nothing ever retried. The salon sat at 'pending' with no review row, which made it
     * INVISIBLE TO THIS CONSOLE FOREVER. The owner waited for a decision that could not be made,
     * the moderator never knew there was anything to decide, and the only trace was one WARN line
     * from days earlier. A best-effort write with no reconciliation is a write you will eventually
     * lose — and here, losing it costs a salon.
     *
     * <h2>Why on read rather than on a schedule</h2>
     * The moment somebody opens the queue is exactly when a stale queue matters, and it needs no
     * scheduler, no lock and no new failure mode. Cost is one call to bmp-salon per queue load.
     *
     * <p>Never throws: a reconciliation failure must not blank the queue. The rows that DO exist
     * are still worth showing.
     */
    private void reconcileMissingReviews() {
        try {
            List<UUID> pending = salons.pendingReview();
            if (pending == null || pending.isEmpty()) return;

            for (UUID salonId : pending) {
                // enqueue() is already idempotent on "has a pending row" (V007, Session 46), so
                // this is safe to run on every load — no duplicate rows.
                if (reviews.findFirstBySalonIdAndStatusOrderBySubmittedAtDesc(salonId, "pending").isEmpty()) {
                    enqueue(salonId);
                    log.warn("Salon {} was pending with no review row — enqueued now. Its original "
                            + "enqueue at signup must have failed; the owner has been waiting.", salonId);
                }
            }
        } catch (Exception e) {
            log.warn("Could not reconcile pending salons against the review queue ({}). The queue "
                    + "still lists every review row that exists.", e.toString());
        }
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
        // V007 (Session 46): "already enqueued" now means "already has a PENDING row", not
        // "has any row at all". The old check used a unique index to absorb bmp-salon's retries,
        // and in doing so also made a second submission after rejection impossible. Asking about
        // the pending row separates those two things: retries are still absorbed, resubmissions
        // are allowed, and a rejection is never overwritten.
        return reviews.findFirstBySalonIdAndStatusOrderBySubmittedAtDesc(salonId, "pending")
                .map(this::toResponse)
                .orElseGet(() -> toResponse(reviews.save(new SalonReview(salonId))));
    }

    /**
     * The salon's most recent review, or empty when it has never been submitted.
     *
     * <p>Empty is a real state, not an error: a salon created before the moderation queue
     * existed, or one whose enqueue call failed. The caller turns it into a 204 so the owner's
     * app can say "not submitted" rather than showing an error for a situation nobody caused.
     */
    public java.util.Optional<SalonReviewResponse> latestFor(UUID salonId) {
        return reviews.findFirstBySalonIdOrderBySubmittedAtDesc(salonId).map(this::toResponse);
    }

    /**
     * A rejected salon comes back after fixing what was wrong. Session 46.
     *
     * <p>Creates a NEW review row rather than reopening the rejected one. {@code decide} refuses
     * to re-decide anything not pending, with a comment that is exactly right — "re-approving a
     * rejection would erase the fact it was ever rejected" — and that guard stays untouched.
     * Reusing the row would have meant relaxing it.
     *
     * <p>Called by bmp-salon on behalf of the owner, so the caller is a service and the
     * ownership check has already happened there. Idempotent in the way that matters: if a
     * pending row already exists this returns it rather than stacking a third submission.
     */
    @Transactional
    public SalonReviewResponse resubmit(UUID salonId, String ownerNote) {
        // Already back in the queue — a double-tap on "Resubmit", or a retry. Not an error:
        // the owner's intent is satisfied and telling them off for it would be strange.
        var pending = reviews.findFirstBySalonIdAndStatusOrderBySubmittedAtDesc(salonId, "pending");
        if (pending.isPresent()) return toResponse(pending.get());

        SalonReview last = reviews.findFirstBySalonIdOrderBySubmittedAtDesc(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "NO_REVIEW: this salon has never been submitted for review."));

        // Only a REJECTED salon may resubmit. An approved one has nothing to resubmit, and a
        // suspended one was stopped deliberately — letting it re-enter the queue would route a
        // moderation decision around the person who made it.
        if (!"rejected".equals(last.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "NOT_REJECTED: this salon is " + last.getStatus() + ", so there is nothing to "
                    + "resubmit." + ("suspended".equals(last.getStatus())
                            ? " A suspended salon has to be reinstated by the BMP team."
                            : ""));
        }

        SalonReview next = reviews.save(new SalonReview(
                salonId, last.getSubmissionCount() + 1, trimToNull(ownerNote)));

        log.info("Salon {} resubmitted for review (submission #{})", salonId, next.getSubmissionCount());
        return toResponse(next);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
                    // Session 46: the note travels WITH the decision so bmp-salon can tell the
                    // owner why. On a rejection this note is the entire message they receive.
                    new SalonServiceClient.StatusChangeRequest(req.decision(), req.note(),
                            // Only an approval carries a rate. Forwarding it on a rejection would
                            // set commission on a salon we just refused — harmless today, and
                            // exactly the kind of stray write that confuses the next reader.
                            "approved".equals(req.decision()) ? req.commissionBps() : null));
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

        /*
         * The owner IS told — and not from here. Session 60, verified.
         *
         * A TODO sat on this line asking for a notification. It was stale: `setSalonStatus` above
         * carries the decision AND the note into bmp-salon, which publishes SalonStatusChanged with
         * the owner's resolved email and a `canResubmit` flag, and the dispatcher sends it
         * (Session 46).
         *
         * Adding a second send here would have double-emailed every rejected owner — which is the
         * specific harm of implementing a stale TODO rather than checking it first. The note is
         * kept as a pointer to where the notification actually lives.
         */
        return toResponse(review);
    }

    /**
     * Freeze, restore or remove a salon OUTSIDE the review flow. Session 48.
     *
     * <h2>Why this had to exist separately</h2>
     * Suspension was only reachable through {@code decide()}, which acts on a REVIEW ROW. A salon
     * that had been approved months ago and was now trading badly had no review to decide on, so
     * the console could not touch it — there was no way to take a live salon off the site at all.
     *
     * <h2>Order of operations</h2>
     * bmp-salon first, exactly like decide(). If that call fails nothing is recorded here, so the
     * console never claims to have frozen a salon that is still taking bookings. A record saying
     * "suspended" beside a salon customers can still book is the worst of both.
     *
     * <p>Always audited: this is a person removing a business's livelihood from the platform, and
     * "who did that, and why" must survive.
     */
    @Transactional
    public void setStatusDirect(UUID salonId, SalonStatusRequest req, StaffPrincipal caller, String ip) {
        /*
         * ── DELETE IS THE MAIN ADMIN'S ALONE. Session 65, Darshan's spec. ───────────────────────
         *
         * One endpoint carries three genuinely different decisions, and the annotation on it can
         * only see the CALLER — not which of the three they are making. So it let anyone at ops
         * and above take a business off the platform entirely.
         *
         *   suspended  freeze. Reversible, the owner is told, and it is an OPERATIONAL call made
         *              during an incident — ops must be able to make it at 10pm.
         *   active     unfreeze. The reverse of the same operational call.
         *   deleted    remove the business from the platform. A different kind of decision.
         *
         * Same shape as StaffAccountScope.requireCanSetStatus, and for the same reason: when one
         * column carries several authorities, the check belongs beside the WRITE where the target
         * value is known, not on the door where it is not.
         *
         * ── WHY DELETE IS HEAVIER THAN SUSPEND, GIVEN BOTH ARE REVERSIBLE ──────────────────────
         * A soft delete keeps the row, so bookings, payouts and audit history survive and an admin
         * can undo it. What it does NOT undo by itself is the owner's SEAT: V027 enforces one owner
         * seat per person, and a deleted salon keeps its seat — so that owner cannot open another
         * salon until somebody clears it by hand. Deleting a salon therefore ends a person's
         * ability to trade on BMP at all, not just this shopfront, and that is a founder decision.
         */
        if ("deleted".equalsIgnoreCase(req.status())
                && !StaffPermission.SUPER_ADMIN.equalsIgnoreCase(caller.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the main admin can remove a salon from the platform. "
                    + "You can suspend it — that hides it and stops bookings immediately, and is "
                    + "reversible by anyone at ops.");
        }

        try {
            salons.setSalonStatus(salonId,
                    // No commission change here — this is not a pricing decision.
                    new SalonServiceClient.StatusChangeRequest(req.status(), req.note(), null));
        } catch (Exception e) {
            log.error("Could not set salon {} to '{}' ({}) — NOTHING was recorded.",
                    salonId, req.status(), e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "SALON_SERVICE_UNAVAILABLE: the salon was not changed. Try again.");
        }

        // Full 10-arg form, matching decide() — the actor's email and role are what make an audit
        // row readable a year later, when the staff account may no longer exist.
        audit.record("bmp_staff", caller.staffId(), "SALON_" + req.status().toUpperCase(),
                "salon", salonId,
                Map.of("status", req.status()),
                ip, caller.email(), caller.role(), req.note());
        log.info("Salon {} set to {} by {} — {}", salonId, req.status(), caller.email(), req.note());
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
        String ownerName = null;
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

        /*
         * Session 48 — the review packet. ownerName and area used to be hardcoded null here, and
         * SalonDto carried only a name, so the moderation screen showed almost nothing. One call
         * per row: the queue is tens of rows, not thousands, and a moderator who has to open a
         * second system to see a phone number will approve without checking.
         */
        SalonReviewDetail detail = null;
        try {
            var m = salons.moderationPacket(r.getSalonId()).getBody();
            if (m != null) {
                if (salonName == null) salonName = m.name();
                ownerName = m.ownerName();
                area = m.area();
                detail = new SalonReviewDetail(
                        m.reference(), m.area(), m.pincode(), m.address(), m.about(),
                        m.imageUrl(), m.lat(), m.lng(), m.mapLink(), m.categories(),
                        // Session 65 — was dropped here, which is why the owner was read-only.
                        m.ownerUserId(),
                        m.ownerName(), m.ownerEmail(), m.ownerPhone(), m.stylistCount(),
                        m.services() == null ? List.of() : m.services().stream()
                                .map(x -> new ReviewServiceItem(x.name(), x.pricePaise(),
                                        x.durationMinutes(), x.archived())).toList(),
                        m.photos() == null ? List.of() : m.photos().stream()
                                .map(x -> new ReviewPhoto(x.url(), x.caption())).toList(),
                        m.createdAt());
            }
        } catch (Exception e) {
            // Degrade to ids rather than failing the row — same trade as the enrichment above.
            log.warn("Could not load the moderation packet for salon {} ({}). The row will render "
                    + "without detail.", r.getSalonId(), e.toString());
        }

        return new SalonReviewResponse(
                r.getId(), r.getSalonId(), salonName, ownerName, area,
                r.getStatus(), r.getSubmittedAt(), r.getDecidedAt(), decidedByName,
                r.getDecisionNote(), checks,
                r.getSubmissionCount(), r.getResubmissionNote(), detail);
    }
}
