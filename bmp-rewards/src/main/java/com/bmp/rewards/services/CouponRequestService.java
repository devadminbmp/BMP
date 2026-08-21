package com.bmp.rewards.services;

import com.bmp.rewards.dto.CouponAdminDtos.CreateCouponRequest;
import com.bmp.rewards.dto.CouponAdminDtos.CouponResponse;
import com.bmp.rewards.dto.CouponAdminDtos.StaffContext;
import com.bmp.rewards.dto.CouponRequestDtos.*;
import com.bmp.rewards.entities.CouponRequest;
import com.bmp.rewards.entities.CouponRequestUser;
import com.bmp.common.events.CouponRequestDecided;
import com.bmp.common.events.CouponRequestRaised;
import com.bmp.common.outbox.OutboxPublisher;
import com.bmp.rewards.client.UserServiceClient;
import com.bmp.rewards.repositories.CouponPolicyRepository;
import com.bmp.rewards.repositories.CouponRepository;
import com.bmp.rewards.repositories.CouponRequestRepository;
import com.bmp.rewards.repositories.CouponRequestUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The approval workflow: asking for a coupon you cannot issue yourself.
 *
 * <h2>What this is for</h2>
 * V003 gave support hard limits and made them configuration. That is the right wall, but a wall
 * with no gate has a predictable failure mode:
 *
 * <blockquote>"The salon cancelled this customer's wedding booking. ₹500 isn't enough."</blockquote>
 *
 * Before this class the answer was a 403 and the end of the conversation — which in practice
 * meant the customer left, or somebody borrowed an admin login "just this once". <b>A refusal
 * with no path forward doesn't enforce a policy; it makes people route around it.</b>
 *
 * <h2>Two requesters, one queue</h2>
 * <ul>
 *   <li><b>Support</b> — above their per-coupon limit or out of allowance.</li>
 *   <li><b>Salon owners</b> — who can NEVER issue directly, because a coupon may be funded from
 *       BMP's commission. "Who pays for this?" is not the beneficiary's question to answer.</li>
 * </ul>
 *
 * <h2>Approval mints the coupon</h2>
 * The approving admin's identity is what {@link CouponAdminService#issue} is called with, so the
 * coupon's provenance records <em>who authorised it</em>, not who asked. That is the honest
 * attribution: the admin is accountable for the decision. The request row keeps the link, so
 * "why does this ₹3,000 coupon exist?" is answerable in one hop.
 */
@Service
public class CouponRequestService {

    private static final Logger log = LoggerFactory.getLogger(CouponRequestService.class);

    /** Enough for one honest sentence. See RaiseRequest's javadoc. */
    private static final int MIN_JUSTIFICATION = 20;
    private static final int DEFAULT_AUTO_EXPIRE_DAYS = 14;

    private final CouponRequestRepository requests;
    private final CouponRequestUserRepository requestUsers;
    private final CouponAdminService couponAdmin;
    private final CouponRepository coupons;
    private final CouponPolicyRepository policyRepo;
    private final OutboxPublisher outbox;
    private final UserServiceClient users;

    public CouponRequestService(CouponRequestRepository requests, CouponRequestUserRepository requestUsers,
                                 CouponAdminService couponAdmin, CouponRepository coupons,
                                 CouponPolicyRepository policyRepo, OutboxPublisher outbox,
                                 UserServiceClient users) {
        this.requests = requests;
        this.requestUsers = requestUsers;
        this.couponAdmin = couponAdmin;
        this.coupons = coupons;
        this.policyRepo = policyRepo;
        this.outbox = outbox;
        this.users = users;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Raising
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Transactional
    public CouponRequestResponse raise(RaiseRequest req, RequesterContext who) {
        if (req.justification() == null || req.justification().trim().length() < MIN_JUSTIFICATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "JUSTIFICATION_TOO_SHORT: say why in at least %d characters — an admin has to decide on this without you in the room."
                            .formatted(MIN_JUSTIFICATION));
        }
        if (!req.activeTo().isAfter(req.activeFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ACTIVE_WINDOW_INVALID: the end must be after the start");
        }
        // Same rule as direct issuing, applied here so the request cannot be approved into an
        // uncapped percentage. Validating only at approval would mean the admin discovers the
        // problem, not the person who can fix it.
        if ("percent".equalsIgnoreCase(req.discountType())
                && (req.maxDiscountPaise() == null || req.maxDiscountPaise() <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MAX_DISCOUNT_REQUIRED: a percentage coupon must have a cap");
        }
        boolean selectedUsers = CouponIssuePolicy.AUDIENCE_SELECTED_USERS.equals(req.audienceType());
        if (selectedUsers && (req.targetUserIds() == null || req.targetUserIds().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "NO_RECIPIENTS: a 'selected users' request needs at least one customer");
        }

        /*
         * A SALON OWNER MAY ONLY ASK FOR THEIR OWN SALON.
         *
         * Without this, an owner could request a platform-wide coupon — funded by BMP — and an
         * admin skimming a queue might approve it as routine. The scope is forced from the
         * authenticated identity rather than read from the body, so it cannot be asked for at
         * all. Refusing at approval time would be too late: by then it looks like a decision
         * someone made.
         */
        String salonScope = req.salonScope() == null ? CouponIssuePolicy.SCOPE_ALL_SALONS : req.salonScope();
        if (CouponRequest.REQUESTER_SALON_OWNER.equals(who.requesterType())) {
            if (who.salonId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "NO_SALON: your account isn't linked to a salon.");
            }
            salonScope = CouponIssuePolicy.SCOPE_SELECTED_SALONS;
        }

        CouponRequest r = new CouponRequest(nextRef(), who.requesterType(), who.requesterId(),
                req.justification().trim());

        /*
         * RESOLVE THE REQUESTER'S CONTACT DETAILS NOW, NOT AT DECISION TIME.
         *
         * bmp-admin already sends name and email for staff. A salon owner arrives from the app
         * with nothing but a user id in their token, so we ask bmp-user — once, here.
         *
         * Two reasons this happens at RAISE and not at APPROVE:
         *   1. The approval runs in a transaction that also mints a coupon. An outbound call in
         *      there means bmp-user being briefly down takes the approval with it, and an admin
         *      sees a failure for something that actually succeeded a moment later.
         *   2. Contact details change. The address we notify should be the one on file when
         *      they asked — that is who we are answering.
         *
         * Best-effort: a failure here must never block the request. Somebody with a real problem
         * is not helped by "we couldn't look up your email, try again". They lose the email, not
         * the request, and the log says which.
         */
        String name = who.name();
        String email = who.email();
        String phone = null;
        if (email == null || name == null) {
            try {
                UserServiceClient.UserContact u = users.getUserById(who.requesterId()).getBody();
                if (u != null) {
                    if (name == null) name = u.name();
                    if (email == null) email = u.email();
                    phone = u.phone();
                }
            } catch (Exception e) {
                log.warn("Could not resolve contact details for requester {} ({}). The request is "
                        + "recorded; they will not be emailed when it is decided.",
                        who.requesterId(), e.toString());
            }
        }
        r.setRequesterName(name);
        r.setRequesterEmail(email);
        r.setRequesterPhone(phone);
        r.setRequesterRole(who.role());
        r.setSalonId(who.salonId());
        r.setIssuedForTicketId(req.ticketId());
        r.setProposedName(req.proposedName());
        r.setAudienceType(req.audienceType());
        r.setSalonScope(salonScope);
        r.setDiscountType(req.discountType());
        r.setValue(req.value());
        r.setMaxDiscountPaise(req.maxDiscountPaise());
        r.setMinSpendPaise(req.minSpendPaise() == null ? 0 : req.minSpendPaise());
        r.setPerUserLimit(req.perUserLimit() == null ? 1 : req.perUserLimit());
        r.setTotalUsageCap(req.totalUsageCap());
        r.setActiveFrom(req.activeFrom());
        r.setActiveTo(req.activeTo());
        // A salon offering to fund its own promotion is the one case where the salon bears it.
        // Recorded as asked; the approving admin can still decide otherwise.
        r.setCommissionBase(Boolean.TRUE.equals(req.salonFunded()) ? "pre_discount" : "post_discount");
        r = requests.save(r);

        if (selectedUsers) {
            for (UUID userId : req.targetUserIds()) {
                requestUsers.save(new CouponRequestUser(r.getId(), userId));
            }
        }

        /*
         * Tell somebody. The queue had nothing pointing at it before this — an approval workflow
         * whose approver is never notified relies on a human remembering to open a screen, which
         * is the same failure the workflow exists to prevent, moved one step along.
         *
         * Onto the OUTBOX, inside this transaction: if the request rolls back, so does the
         * notification. The alternative — sending directly — produces "your request has been
         * received" emails for requests that do not exist.
         */
        outbox.publish(new CouponRequestRaised(
                r.getId(), r.getRequestRef(), r.getRequesterType(),
                r.getRequesterName() != null ? r.getRequesterName() : r.getRequesterEmail(),
                summarise(r), r.getJustification()));

        log.info("Coupon request {} raised by {} ({}) — {} {} for {}",
                r.getRequestRef(), who.email(), who.requesterType(),
                req.discountType(), req.value(), req.audienceType());
        return toResponse(r);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Deciding
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Approve — optionally granting less than was asked — and mint the coupon.
     *
     * <p>The coupon is created with the APPROVER's staff context, so
     * {@link CouponIssuePolicy#assertMayIssue} sees an admin role and the support limits don't
     * apply. That is the point of the whole flow: the restriction is on who may DECIDE, not on
     * what can exist.
     */
    @Transactional
    public CouponRequestResponse approve(UUID requestId, DecisionRequest decision, StaffContext approver) {
        CouponRequest r = mustFindPending(requestId);

        if (decision.value() != null) r.setApprovedValue(decision.value());
        if (decision.maxDiscountPaise() != null) r.setApprovedMaxDiscountPaise(decision.maxDiscountPaise());
        if (decision.activeTo() != null) r.setApprovedActiveTo(decision.activeTo());

        if (r.effectiveActiveTo().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WINDOW_ALREADY_PAST: this would create a coupon that has already expired. "
                    + "Extend the end date as part of approving it.");
        }

        List<UUID> recipients = requestUsers.findByRequestId(r.getId()).stream()
                .map(CouponRequestUser::getUserId).toList();

        CreateCouponRequest mint = new CreateCouponRequest(
                null,                                   // generate the code
                r.getProposedName(),
                "Approved from request " + r.getRequestRef(),
                couponTypeFor(r),
                r.getAudienceType(),
                r.getSalonScope(),
                r.getDiscountType(),
                r.effectiveValue(),
                r.effectiveMaxDiscountPaise(),
                r.getMinSpendPaise(),
                r.getPerUserLimit(),
                r.getTotalUsageCap(),
                r.getActiveFrom(),
                r.effectiveActiveTo(),
                false,                                  // wallet stacking off by default
                recipients.isEmpty() ? null : recipients,
                r.getSalonId() == null ? null : List.of(r.getSalonId()),
                "pre_discount".equals(r.getCommissionBase()),
                r.getIssuedForTicketId(),
                // The justification travels onto the coupon itself, so the reason survives even
                // if someone only ever looks at the coupon list.
                "Request %s: %s".formatted(r.getRequestRef(), r.getJustification()));

        CouponResponse created = couponAdmin.issue(mint, approver);

        r.setStatus(CouponRequest.STATUS_APPROVED);
        r.setCreatedCouponId(created.id());
        stampDecision(r, approver, decision.note());

        notifyDecided(r, true, created.code());

        log.warn("Coupon request {} APPROVED by {} — coupon {} ({}) issued to requester {}",
                r.getRequestRef(), approver.email(), created.code(), created.id(), r.getRequesterEmail());
        return toResponse(r);
    }

    /** Reject. A note is required — a refusal with no reason gets re-asked verbatim tomorrow. */
    @Transactional
    public CouponRequestResponse reject(UUID requestId, DecisionRequest decision, StaffContext approver) {
        CouponRequest r = mustFindPending(requestId);
        if (decision.note() == null || decision.note().trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "REASON_REQUIRED: tell the requester why, in at least 10 characters. "
                    + "They have a customer waiting and will otherwise just ask again.");
        }
        r.setStatus(CouponRequest.STATUS_REJECTED);
        stampDecision(r, approver, decision.note());

        // A rejection is at LEAST as important to deliver as an approval: the requester has a
        // customer waiting and, until they're told, is still waiting too.
        notifyDecided(r, false, null);

        log.info("Coupon request {} rejected by {}: {}", r.getRequestRef(), approver.email(), decision.note());
        return toResponse(r);
    }

    /**
     * Withdrawn by whoever raised it — "sorted it another way".
     *
     * <p>A separate status from rejected on purpose. One is a refusal and one isn't, and
     * collapsing them makes any approval-rate number meaningless.
     */
    @Transactional
    public CouponRequestResponse cancel(UUID requestId, UUID requesterId) {
        CouponRequest r = mustFindPending(requestId);
        if (!r.getRequesterId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_YOURS: you can only withdraw your own request.");
        }
        r.setStatus(CouponRequest.STATUS_CANCELLED);
        r.touch();
        return toResponse(r);
    }

    /**
     * Expire anything pending past the cut-off.
     *
     * <p>A goodwill gesture approved three weeks after the complaint is worse than a prompt no —
     * it reopens a closed conversation and reads as an afterthought. Expiring also stops the
     * queue accumulating requests nobody will action, which is how a queue stops being read.
     *
     * <p>Nothing schedules this yet; it is called on read of the queue. A cron would be better
     * and is a one-liner once anything else in the platform needs scheduling.
     */
    @Transactional
    public int expireStale() {
        int days = (int) policyLong("request_auto_expire_days", DEFAULT_AUTO_EXPIRE_DAYS);
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        List<CouponRequest> stale = requests.findByStatusAndCreatedAtBefore(CouponRequest.STATUS_PENDING, cutoff);
        for (CouponRequest r : stale) {
            r.setStatus(CouponRequest.STATUS_EXPIRED);
            r.setDecisionNote("Expired automatically after %d days with no decision.".formatted(days));
            r.touch();
        }
        if (!stale.isEmpty()) {
            log.warn("Expired {} coupon request(s) older than {} days — nobody decided on them.", stale.size(), days);
        }
        return stale.size();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Reading
    // ═══════════════════════════════════════════════════════════════════════════════════════

    public List<CouponRequestResponse> queue() {
        expireStale();
        return requests.findByStatusOrderByCreatedAtAsc(CouponRequest.STATUS_PENDING)
                .stream().map(this::toResponse).toList();
    }

    public List<CouponRequestResponse> all() {
        return requests.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    public List<CouponRequestResponse> mine(UUID requesterId) {
        return requests.findByRequesterIdOrderByCreatedAtDesc(requesterId).stream().map(this::toResponse).toList();
    }

    public List<CouponRequestResponse> forSalon(UUID salonId) {
        return requests.findBySalonIdOrderByCreatedAtDesc(salonId).stream().map(this::toResponse).toList();
    }

    public long pendingCount() {
        return requests.countByStatus(CouponRequest.STATUS_PENDING);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Emit the decision, so the requester finds out without opening a screen.
     *
     * <p>`grantedNote` is populated only when the approver changed the figure — and it is the
     * single most important line in the message. A requester who misses it quotes the ORIGINAL
     * amount to a customer and has the argument at their own counter.
     */
    private void notifyDecided(CouponRequest r, boolean approved, String couponCode) {
        String grantedNote = null;
        if (approved && r.getApprovedValue() != null && r.getApprovedValue() != r.getValue()) {
            grantedNote = "You asked for %s and %s was approved."
                    .formatted(describe(r.getDiscountType(), r.getValue()),
                               describe(r.getDiscountType(), r.getApprovedValue()));
        }
        outbox.publish(new CouponRequestDecided(
                r.getId(), r.getRequestRef(), approved,
                r.getRequesterId(), r.getRequesterName(),
                r.getRequesterEmail(), r.getRequesterPhone(),
                couponCode, grantedNote, r.getDecisionNote()));
    }

    /** One line an approver can triage from a phone without opening the console. */
    private String summarise(CouponRequest r) {
        String who = CouponRequest.REQUESTER_SALON_OWNER.equals(r.getRequesterType())
                ? "a salon" : "support";
        return "%s asked for %s (%s)".formatted(
                who, describe(r.getDiscountType(), r.getValue()), r.getAudienceType().replace('_', ' '));
    }

    /** Flat coupons are paise; percentage coupons are basis points. Never render one as the other. */
    private String describe(String discountType, long value) {
        return "percent".equalsIgnoreCase(discountType)
                ? (value / 100) + "%"
                : "Rs " + (value / 100);
    }

    private CouponRequest mustFindPending(UUID id) {
        CouponRequest r = requests.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (!r.isPending()) {
            // 409 rather than 400: the request is fine, the state isn't. Two admins opening the
            // same queue is normal, and the second one should be told what happened.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ALREADY_%s: this request was already decided.".formatted(r.getStatus().toUpperCase()));
        }
        return r;
    }

    private void stampDecision(CouponRequest r, StaffContext approver, String note) {
        r.setDecidedByStaffId(approver.staffId());
        r.setDecidedByEmail(approver.email());
        r.setDecidedAt(Instant.now());
        if (note != null && !note.isBlank()) r.setDecisionNote(note.trim());
        r.touch();
    }

    /** Salon-scoped requests are salon_specific; everything else is a platform gesture. */
    private String couponTypeFor(CouponRequest r) {
        return CouponIssuePolicy.SCOPE_SELECTED_SALONS.equals(r.getSalonScope()) ? "salon_specific" : "win_back";
    }

    /** CR-2026-00042. Same count-based scheme as bookingRef — see BookingService for the caveat. */
    private String nextRef() {
        String prefix = "CR-" + Instant.now().atZone(ZoneOffset.UTC).getYear() + "-";
        long seq = requests.count() + 1;
        return prefix + String.format("%05d", seq);
    }

    private CouponRequestResponse toResponse(CouponRequest r) {
        String code = r.getCreatedCouponId() == null ? null
                : coupons.findById(r.getCreatedCouponId()).map(c -> c.getCode()).orElse(null);
        return new CouponRequestResponse(
                r.getId(), r.getRequestRef(), r.getStatus(),
                r.getRequesterType(), r.getRequesterId(), r.getRequesterName(), r.getRequesterEmail(),
                r.getRequesterRole(), r.getSalonId(),
                r.getJustification(), r.getIssuedForTicketId(),
                r.getProposedName(), r.getAudienceType(), r.getSalonScope(),
                r.getDiscountType(), r.getValue(), r.getMaxDiscountPaise(),
                r.getMinSpendPaise(), r.getPerUserLimit(), r.getTotalUsageCap(),
                r.getActiveFrom(), r.getActiveTo(), r.getCommissionBase(),
                requestUsers.findByRequestId(r.getId()).size(),
                r.getApprovedValue(), r.getApprovedMaxDiscountPaise(), r.getApprovedActiveTo(),
                r.getDecidedByStaffId(), r.getDecidedByEmail(), r.getDecidedAt(), r.getDecisionNote(),
                r.getCreatedCouponId(), code,
                r.getCreatedAt(), r.getUpdatedAt());
    }

    private long policyLong(String key, long fallback) {
        return policyRepo.findByPolicyKey(key)
                .map(p -> {
                    try {
                        return Long.parseLong(p.getPolicyValue());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }
}
