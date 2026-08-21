package com.bmp.booking.services;

import com.bmp.common.money.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * What it costs to cancel, and whether it can still be moved — read from the terms this booking
 * was made under, never from today's policy.
 *
 * <h2>Why this class exists (Session 37)</h2>
 * {@code policy_snapshot} has carried the salon's real cancellation terms since Session 30, on a
 * column whose own migration comment says "FROZEN copy of salon_policy, never changes". Nothing
 * ever opened it. A customer cancelling two minutes before their appointment and one cancelling
 * three weeks out got exactly the same outcome: no fee, no record, no difference.
 *
 * <p>Freezing terms nobody reads is worse than not freezing them, because it looks solved.
 *
 * <h2>The rule this class enforces above all others</h2>
 * <b>Everything here comes out of the snapshot.</b> Not one value is fetched from bmp-salon.
 * That is the entire point of a snapshot: a salon that tightens its cancellation window on
 * Tuesday must not retroactively change what a customer agreed to on Monday. A single live
 * lookup in this file would quietly undo Session 30's work, and the failure would be invisible
 * until somebody disputed a charge.
 *
 * <h2>And the clock runs against the ORIGINAL appointment</h2>
 * Not against wherever the booking has since been rescheduled to — otherwise:
 *
 * <ol>
 *   <li>Book Saturday 11:00, free-cancel window 24 hours.</li>
 *   <li>At 10:00 on Saturday — one hour out, deep in the fee window — reschedule to next month.</li>
 *   <li>Cancel, now "three weeks ahead". Free.</li>
 * </ol>
 *
 * The salon lost Saturday's slot with an hour's notice and was paid nothing. A salon can opt out
 * ({@code rescheduleKeepsOriginalClock}), and that choice is itself frozen per booking.
 */
public final class CancellationTerms {

    private static final Logger log = LoggerFactory.getLogger(CancellationTerms.class);

    // Reasons, mirroring booking.cancellation_fee_reason.
    public static final String FREE = "free";
    public static final String LATE = "late";
    public static final String NO_NOTICE = "no_notice";
    public static final String SALON_CANCELLED = "salon_cancelled";
    public static final String NO_POLICY = "no_policy";

    /**
     * Platform fallbacks, used only when a booking's snapshot predates a field or is unreadable.
     *
     * <p><b>Every fee default is ZERO.</b> An unreadable snapshot must never produce a charge:
     * if we cannot say what the customer agreed to, we cannot claim they agreed to pay. The
     * error goes in the log, not on the customer's bill.
     */
    private static final int DEFAULT_FREE_CANCEL_HOURS = 24;
    private static final int DEFAULT_LATE_CANCEL_HOURS = 2;
    private static final int DEFAULT_RESCHEDULE_NOTICE_HOURS = 24;
    private static final int DEFAULT_MAX_RESCHEDULES = 2;

    private CancellationTerms() {}

    /**
     * The decision. {@code feeBps}/{@code feePaise} are what gets written to the booking and
     * shown to the customer BEFORE they confirm — the preview and the charge are the same
     * calculation, called twice, so they cannot disagree.
     *
     * @param hoursUntil hours from now to the original appointment. Negative once it has passed.
     */
    public record Decision(int feeBps, long feePaise, String reason, long hoursUntil) {
        public boolean isFree() {
            return feePaise == 0;
        }
    }

    /** Whether a booking can still be moved, and why not when it can't. */
    public record RescheduleCheck(boolean allowed, String refusal, int noticeHours, int used, int max) {}

    /**
     * Compute the fee for cancelling {@code booking} right now.
     *
     * @param policySnapshotJson the booking's frozen terms
     * @param originalStart      when the appointment was FIRST booked for — see the class note
     * @param currentStart       where it sits now; used only when the salon opted out of the
     *                           original-clock rule
     * @param finalAmountPaise   the basket the fee is a percentage of
     * @param bySalon            a salon-caused cancellation is always free, whatever the timing
     */
    public static Decision forCancellation(String policySnapshotJson, ObjectMapper mapper,
                                            Instant originalStart, Instant currentStart,
                                            long finalAmountPaise, boolean bySalon, String bookingRef) {
        JsonNode snap = parse(policySnapshotJson, mapper, bookingRef);

        Instant anchor = bool(snap, "rescheduleKeepsOriginalClock", true) ? originalStart : currentStart;
        if (anchor == null) {
            anchor = currentStart;
        }
        long hoursUntil = anchor == null
                ? 0
                : Duration.between(Instant.now(), anchor).toHours();

        /*
         * A salon-caused cancellation is ALWAYS free. Checked before any band, deliberately, so
         * no combination of policy values can produce a charge here.
         *
         * Charging a customer because the salon's stylist quit would be indefensible, and a
         * platform that let it happen once would deserve the review it got. This is also why
         * `salon_cancelled` is a distinct reason rather than reusing `free`: support needs to
         * see at a glance that the salon caused it.
         */
        if (bySalon) {
            return new Decision(0, 0L, SALON_CANCELLED, hoursUntil);
        }

        // No readable snapshot means no agreed terms, which means no fee. Never guess upwards.
        if (snap == null) {
            return new Decision(0, 0L, NO_POLICY, hoursUntil);
        }

        int freeHours = intOr(snap, "freeCancelHours", DEFAULT_FREE_CANCEL_HOURS);
        int lateHours = intOr(snap, "lateCancelHours", DEFAULT_LATE_CANCEL_HOURS);
        int lateBps = intOr(snap, "lateCancelFeeBps", 0);
        int noNoticeBps = intOr(snap, "noNoticeFeeBps", 0);

        int bps;
        String reason;
        if (hoursUntil >= freeHours) {
            bps = 0;
            reason = FREE;
        } else if (hoursUntil >= lateHours) {
            bps = lateBps;
            reason = LATE;
        } else {
            // Includes appointments already in the past — hoursUntil is negative there, which
            // falls through to the strictest band, which is right: cancelling after your slot
            // has started is the least notice possible.
            bps = noNoticeBps;
            reason = NO_NOTICE;
        }

        /*
         * Integer paise throughout, via Money.percentBps — the same helper commission uses.
         * Deliberately NOT `amount * bps / 10000` written inline: this multiplies a real
         * person's money, and a second implementation of the same rounding is a second answer
         * waiting to disagree with the first.
         */
        long feePaise = bps == 0 ? 0L : Money.ofPaise(finalAmountPaise).percentBps(bps).paise();
        return new Decision(bps, feePaise, reason, hoursUntil);
    }

    /**
     * Can the CUSTOMER still move this booking?
     *
     * <p>Two independent limits, and the refusals say which one was hit. "You can't reschedule"
     * with no reason is the kind of message that produces a support ticket; "changes need 24
     * hours' notice" is one a customer can act on — usually by cancelling instead, while it is
     * still free.
     *
     * @param used how many times the CUSTOMER has already moved it. Salon moves don't count:
     *             the customer didn't ask for those and shouldn't lose an allowance to them.
     */
    public static RescheduleCheck forCustomerReschedule(String policySnapshotJson, ObjectMapper mapper,
                                                         Instant currentStart, int used, String bookingRef) {
        JsonNode snap = parse(policySnapshotJson, mapper, bookingRef);
        int noticeHours = intOr(snap, "rescheduleNoticeHours", DEFAULT_RESCHEDULE_NOTICE_HOURS);
        int max = intOr(snap, "maxReschedulesPerBooking", DEFAULT_MAX_RESCHEDULES);

        if (max == 0) {
            return new RescheduleCheck(false,
                    "This salon doesn't allow changing a booking — please cancel and book again.",
                    noticeHours, used, max);
        }
        if (used >= max) {
            return new RescheduleCheck(false,
                    "You've already changed this booking " + used + " time" + (used == 1 ? "" : "s")
                    + ", which is this salon's limit. Please cancel and book again.",
                    noticeHours, used, max);
        }

        long hoursUntil = currentStart == null ? 0 : Duration.between(Instant.now(), currentStart).toHours();
        if (hoursUntil < noticeHours) {
            return new RescheduleCheck(false,
                    "This salon needs " + noticeHours + " hours' notice to change a booking, and "
                    + "your appointment is " + (hoursUntil <= 0 ? "already due" : "in " + hoursUntil + " hours")
                    + ". You can still cancel it.",
                    noticeHours, used, max);
        }
        return new RescheduleCheck(true, null, noticeHours, used, max);
    }

    // ---- snapshot reading -------------------------------------------------------------------
    //
    // Every accessor tolerates a missing field, because snapshots written before Session 37 do
    // not carry the V010 keys. An OLD booking must keep behaving exactly as it did — which, for
    // fees, means charging nothing.

    private static JsonNode parse(String json, ObjectMapper mapper, String bookingRef) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            // Loud, because it means a booking's frozen terms are unreadable — and quiet in its
            // effect, because the caller then charges nothing.
            log.error("Booking {} has an unreadable policy_snapshot, so its cancellation terms "
                    + "cannot be applied. Treating the cancellation as free. {}", bookingRef, e.toString());
            return null;
        }
    }

    private static int intOr(JsonNode node, String field, int fallback) {
        return node != null && node.hasNonNull(field) ? node.get(field).asInt(fallback) : fallback;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        return node != null && node.hasNonNull(field) ? node.get(field).asBoolean(fallback) : fallback;
    }
}
