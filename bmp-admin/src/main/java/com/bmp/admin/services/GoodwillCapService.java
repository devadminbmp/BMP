package com.bmp.admin.services;

import com.bmp.admin.client.BookingServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * Nobody gives back more than the customer paid. Session 59.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE RULE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Darshan: <i>"support or anyone can't give coupon price more than what customer had booked."</i>
 *
 * <p><b>Anyone</b> — including the platform owner. This is the one limit in the system that is not
 * a role band, and it deliberately sits OUTSIDE {@code AuthorityService}: the authority matrix
 * answers "is this person senior enough?", and this answers "is this amount sane at all?". A ₹9,000
 * apology for a ₹400 haircut is not a seniority question, and an owner tired at 11pm should be
 * stopped by it exactly as an agent would be.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * CHECKED TWICE, AND WHY THAT IS NOT PARANOIA
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Once when the request is raised, and again immediately before the action executes.
 *
 * <p>The gap between those two moments is real: a request raised on Monday can be approved on
 * Thursday, and in between the booking may have been partially refunded, cancelled with a fee, or
 * rescheduled to a cheaper service. The amount that was within the cap on Monday can exceed it by
 * Thursday. Checking only at raise time would let an approver rubber-stamp something that has since
 * become wrong; checking only at execution would let an agent raise nonsense and waste an
 * approver's attention.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT COUNTS AS "WHAT THEY PAID"
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code finalAmountPaise} — after any discount, which is the money that actually changed hands.
 * Using the gross amount would let a customer who already had a ₹300 coupon receive goodwill
 * against a price they never paid, and do it repeatedly.
 *
 * <p>Refunds already issued are subtracted. Otherwise a ₹500 booking could be refunded ₹500 and
 * then given a ₹500 coupon, which is the platform paying twice for one bad haircut.
 */
@Service
public class GoodwillCapService {

    private static final Logger log = LoggerFactory.getLogger(GoodwillCapService.class);

    /**
     * The actions this applies to: anything that hands value back to a customer.
     *
     * <p>An ALLOW-list, so a new action is uncapped only if somebody deliberately leaves it out —
     * and the reviewer of that omission has to justify it. A block-list would silently exempt
     * every action nobody thought about.
     *
     * <p>Suspensions and erasures are absent because they move no money.
     */
    private static final Set<String> CAPPED_ACTIONS =
            Set.of("coupon.issue", "refund.issue", "wallet.credit", "booking.waive_fee");

    private final BookingServiceClient bookings;
    /**
     * V012, Session 59 — non-refund goodwill already given on this booking.
     *
     * <p>Before this existed the cap could only see refunds, because those are recorded on the
     * booking itself. Coupons and wallet credits were invisible to it, so a ₹600 booking accepted
     * two ₹500 coupons and each one passed. The rule was enforced against one kind of goodwill and
     * silently not the others.
     */
    private final com.bmp.admin.repositories.GoodwillGrantRepository grants;

    public GoodwillCapService(BookingServiceClient bookings,
                               com.bmp.admin.repositories.GoodwillGrantRepository grants) {
        this.bookings = bookings;
        this.grants = grants;
    }

    public static boolean applies(String actionType) {
        return CAPPED_ACTIONS.contains(actionType);
    }

    /**
     * Refuse anything above what this booking is actually worth.
     *
     * <h2>No booking, no cap — and that is deliberate, not an oversight</h2>
     * Some goodwill is not about one appointment: an account-wide apology after an outage, a
     * gesture to somebody who has never managed to book at all. Those have no booking to measure
     * against, and inventing a ceiling for them would be a number with no meaning behind it.
     *
     * <p>They are still governed by the authority matrix, still need approval above a band, and
     * still appear in the goodwill ledger — so an unbounded gesture is visible rather than
     * unchecked. What is refused is the case where a booking IS named and the amount exceeds it.
     *
     * <h2>bmp-booking unreachable REFUSES</h2>
     * Same reasoning as the review check in Session 54: money going out on an unverified amount is
     * permanent, and "the service was down" is not something a customer's refund can be undone
     * over. Failing open here would make the whole cap advisory during exactly the incidents that
     * generate the most goodwill requests.
     */
    public void assertWithinBookingValue(String actionType, UUID bookingId, long valuePaise) {
        if (!applies(actionType) || bookingId == null) {
            return;
        }

        long alreadyGivenBack;
        long paid;
        try {
            var booking = bookings.getById(bookingId);
            if (booking == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That booking doesn't exist, so there's nothing to measure this against.");
            }
            paid = booking.finalAmountPaise();
            /*
             * Two sources, no overlap by construction.
             *
             *   totalRefundedPaise  — refunds, authoritative in bmp-booking
             *   goodwill_grant      — everything else, and a CHECK constraint in V012 forbids
             *                         refund rows from being written there
             *
             * Adding them is therefore safe. If that constraint is ever removed this line
             * double-counts every refund and halves each customer's real ceiling.
             */
            alreadyGivenBack = booking.totalRefundedPaise() + grants.totalForBooking(bookingId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not read booking {} to cap a {} of {}p ({}). REFUSING — an unverified "
                    + "amount leaving the business is permanent.", bookingId, actionType, valuePaise,
                    e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't check the booking's value just now. Nothing has been issued — "
                    + "please try again in a moment.");
        }

        long remaining = Math.max(0, paid - alreadyGivenBack);

        if (valuePaise > remaining) {
            log.warn("Refused a {} of {}p against booking {} — the customer paid {}p and {}p has "
                    + "already been given back, leaving {}p.",
                    actionType, valuePaise, bookingId, paid, alreadyGivenBack, remaining);

            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That's more than the customer paid. This booking was "
                    + rupees(paid)
                    + (alreadyGivenBack > 0
                            ? ", and " + rupees(alreadyGivenBack) + " has already been returned"
                            : "")
                    + " — you can offer up to " + rupees(remaining) + ".");
        }
    }

    /**
     * What may still be given back on this booking — so a form can show the ceiling BEFORE somebody
     * types a number and promises it to a customer.
     *
     * <p>Returns null when there is no booking to measure against, which the UI renders as "no
     * booking limit" rather than as zero. Zero and "not applicable" are opposite answers, and
     * showing the wrong one would either block legitimate goodwill or imply a limit that isn't
     * there.
     */
    public Long remainingFor(UUID bookingId) {
        if (bookingId == null) return null;
        try {
            var booking = bookings.getById(bookingId);
            if (booking == null) return null;
            return Math.max(0, booking.finalAmountPaise()
                    - booking.totalRefundedPaise()
                    - grants.totalForBooking(bookingId));
        } catch (Exception e) {
            // A hint, not a control — the real check throws. Returning null here degrades the form
            // to "no hint" rather than failing a screen somebody is reading during a complaint.
            log.debug("Could not read booking {} for a goodwill hint ({})", bookingId, e.toString());
            return null;
        }
    }

    /**
     * Record that goodwill was actually given, so the next check can see it.
     *
     * <h2>Call this AFTER the thing succeeds, never before</h2>
     * A grant written before the coupon is created would consume the customer's ceiling for a
     * coupon that then failed to issue — and the person retrying would be refused with a message
     * about money the customer never received.
     *
     * <h2>Silent for refunds and for bookingless goodwill, on purpose</h2>
     * Refunds are already counted from the booking (see V012's CHECK constraint); writing them here
     * would double-count. Account-wide gestures with no booking have nothing to be capped against,
     * so there is nothing to record — {@link #assertWithinBookingValue} lets those through for the
     * same reason.
     *
     * <h2>Never throws</h2>
     * The customer has their coupon by the time this runs. Failing the request now would tell an
     * agent the gesture did not happen when it did, and they would issue it again. A missed row
     * loosens a future cap by one grant; a false failure duplicates real money. The former is
     * logged loudly and is the lesser harm.
     *
     * @param approvalRequestId null when this was within the actor's own authority. When present it
     *                          is unique (V012), so re-executing a retried approval cannot
     *                          double-count.
     */
    @org.springframework.transaction.annotation.Transactional
    public void record(String actionType, UUID bookingId, long valuePaise,
                        UUID approvalRequestId, UUID staffId, String role, String reference) {
        if (bookingId == null || valuePaise <= 0) return;
        if (!applies(actionType) || "refund.issue".equals(actionType)) return;

        try {
            if (approvalRequestId != null && grants.existsByApprovalRequestId(approvalRequestId)) {
                log.debug("Goodwill for approval {} is already recorded — not counting it twice.",
                        approvalRequestId);
                return;
            }
            grants.save(com.bmp.admin.entities.GoodwillGrant.of(
                    bookingId, actionType, valuePaise, approvalRequestId, staffId, role, reference));
            log.info("Recorded {}p of {} against booking {} by {}.",
                    valuePaise, actionType, bookingId, role);
        } catch (Exception e) {
            log.error("GAVE {}p of {} on booking {} but FAILED to record it ({}). The customer has "
                    + "it; the cap will not count it, so this booking can now receive slightly more "
                    + "goodwill than it should. Investigate rather than ignore.",
                    valuePaise, actionType, bookingId, e.toString(), e);
        }
    }

    /**
     * What is left, and what has already gone out — in one call, for the form.
     *
     * <h2>Why the history comes with the number</h2>
     * {@link #remainingFor} alone produces "you can offer up to ₹200" on a ₹600 booking, and an
     * agent's first reaction is that the cap is broken. With the two ₹200 coupons a colleague
     * already gave listed beside it, the same number is obviously right — and the agent now knows
     * something about the case that changes what they say to the customer.
     *
     * <p>Best-effort like the hint it feeds: a failure degrades the form to no hint rather than
     * blocking a screen somebody is reading mid-complaint. The real refusal is
     * {@link #assertWithinBookingValue}, which throws.
     */
    public record GoodwillContext(Long remainingPaise, long alreadyGivenPaise,
                                   java.util.List<PastGesture> history) {}

    public record PastGesture(String actionType, long valuePaise, String byRole,
                               java.time.Instant at, String reference) {}

    public GoodwillContext contextFor(UUID bookingId) {
        if (bookingId == null) {
            return new GoodwillContext(null, 0, java.util.List.of());
        }
        java.util.List<PastGesture> past = java.util.List.of();
        long given = 0;
        try {
            past = grants.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                    .map(g -> new PastGesture(g.getActionType(), g.getValuePaise(),
                            g.getGrantedByRole(), g.getCreatedAt(), g.getReference()))
                    .toList();
            given = past.stream().mapToLong(PastGesture::valuePaise).sum();
        } catch (Exception e) {
            log.debug("Could not read goodwill history for booking {} ({})", bookingId, e.toString());
        }
        return new GoodwillContext(remainingFor(bookingId), given, past);
    }

    private static String rupees(long paise) {
        return "₹" + (paise / 100);
    }
}
