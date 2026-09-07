package com.bmp.salon.services;

import com.bmp.salon.entities.Stylist;
import com.bmp.salon.entities.StylistAvailability;
import com.bmp.salon.entities.StylistLeaveRequest;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.StylistAvailabilityRepository;
import com.bmp.salon.repositories.StylistLeaveRequestRepository;
import com.bmp.common.time.BmpTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Leave: asked for by a stylist, answered by the salon. V023 (Session 49).
 *
 * <h2>The one idea worth holding on to</h2>
 * <b>Approval writes {@code stylist_availability} rows. Those rows are what removes the stylist
 * from bookings.</b> Nothing in the booking path reads {@code stylist_leave_request} — it never
 * needs to, because by the time a date matters the approval has already been translated into the
 * interval table the algorithm has read since V003.
 *
 * <p>This is why Darshan's requirement — "leave requested a month ago, and when that day comes
 * the stylist is automatically out of bookings" — needs no scheduled job, no daily sweep, and no
 * cache to invalidate. The availability query on the day simply finds a blocking row. The
 * mechanism was already there; what was missing was anything that could create the row.
 *
 * <h2>What a PENDING request does: nothing</h2>
 * It writes no availability rows and blocks no bookings. Blocking on request would mean a
 * request the owner would have declined has already cost the salon a day of trade, invisibly.
 */
@Service
public class StylistLeaveService {

    private static final Logger log = LoggerFactory.getLogger(StylistLeaveService.class);

    /**
     * A single request may not span more than this. Not a policy about how much leave anyone
     * gets — it is a guard against a mis-typed year turning into 400 blocking rows in one
     * transaction, and against somebody blocking their calendar until 2030 by accident.
     */
    private static final int MAX_DAYS_PER_REQUEST = 90;

    private final StylistLeaveRequestRepository requests;
    private final StylistAvailabilityRepository availability;
    private final StylistSelfService stylistSelf;
    /** Session 49 — telling the stylist what was decided. See publishDecision. */
    private final com.bmp.common.outbox.OutboxPublisher outbox;

    public StylistLeaveService(StylistLeaveRequestRepository requests,
                                StylistAvailabilityRepository availability,
                                StylistSelfService stylistSelf,
                                com.bmp.common.outbox.OutboxPublisher outbox) {
        this.requests = requests;
        this.availability = availability;
        this.stylistSelf = stylistSelf;
        this.outbox = outbox;
    }

    // ══ the stylist's side ════════════════════════════════════════════════════════════════════

    /**
     * Ask for time off.
     *
     * @param userId the CALLER. The stylist id and the salon are derived from it, never sent —
     *               otherwise a stylist could file leave against a colleague.
     */
    @Transactional
    public StylistLeaveRequest request(UUID userId, LocalDate startsOn, LocalDate endsOn,
                                        LocalTime startTime, LocalTime endTime,
                                        String leaveType, String reason) {
        Stylist me = stylistSelf.myProfile(userId);
        StylistSalon link = stylistSelf.activeLink(me.getId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT,
                        "NOT_AT_A_SALON: you need to be on a salon's team before you can ask "
                        + "them for leave."));

        validateRange(startsOn, endsOn, startTime, endTime);

        /*
         * Overlap check. Somebody with the 10th–12th approved who then asks for the 11th is
         * duplicating, not requesting — and two approved leaves over the same day means
         * cancelling one deletes availability rows the other still needs, quietly putting them
         * back on the calendar for a day they are away.
         */
        List<StylistLeaveRequest> clashes = requests.findOverlapping(me.getId(), startsOn, endsOn);
        if (!clashes.isEmpty()) {
            StylistLeaveRequest c = clashes.get(0);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "LEAVE_OVERLAPS: you already have " + c.getStatus() + " leave from "
                    + c.getStartsOn() + " to " + c.getEndsOn() + ". Cancel that first if you "
                    + "need to change the dates.");
        }

        StylistLeaveRequest r = new StylistLeaveRequest(me.getId(), link.getSalonId(),
                startsOn, endsOn, startTime, endTime, leaveType, trimTo(reason, 500));
        requests.save(r);
        log.info("Stylist {} requested {} day(s) leave at salon {} from {} to {}",
                me.getId(), r.days(), link.getSalonId(), startsOn, endsOn);
        return r;
    }

    public List<StylistLeaveRequest> myLeave(UUID userId) {
        return requests.findByStylistIdOrderByStartsOnDesc(stylistSelf.myProfile(userId).getId());
    }

    /**
     * The stylist withdraws — before OR after approval.
     *
     * <p>After approval matters: plans change, and a stylist who no longer needs Tuesday off
     * should be able to hand the slot back rather than leave the salon short for a day they are
     * actually there. Cancelling deletes the availability rows, so they become bookable again
     * immediately.
     */
    @Transactional
    public StylistLeaveRequest withdraw(UUID userId, UUID requestId) {
        Stylist me = stylistSelf.myProfile(userId);
        StylistLeaveRequest r = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND"));
        // Ownership. The controller authorises the CALLER; the id in the path still has to be
        // checked against them, or anyone could withdraw anyone's leave by guessing.
        if (!r.getStylistId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND");
        }
        return cancelInternal(r, "withdrawn by the stylist");
    }

    // ══ the salon's side ══════════════════════════════════════════════════════════════════════

    public List<StylistLeaveRequest> pendingFor(UUID salonId) {
        return requests.findBySalonIdAndStatusOrderByStartsOnAsc(salonId, StylistLeaveRequest.PENDING);
    }

    public List<StylistLeaveRequest> allFor(UUID salonId) {
        return requests.findBySalonIdOrderByStartsOnDesc(salonId);
    }

    /**
     * Approve or decline. <b>Approving is what takes the stylist off the calendar.</b>
     *
     * @param salonId from the caller's token, never the body — so a salon can only decide its own
     */
    @Transactional
    public StylistLeaveRequest decide(UUID salonId, UUID requestId, boolean approve,
                                       UUID deciderUserId, String note) {
        StylistLeaveRequest r = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND"));
        if (!r.getSalonId().equals(salonId)) {
            // Same 404 as "doesn't exist" — a different message would let one salon probe which
            // request ids belong to another.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND");
        }
        if (!r.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ALREADY_DECIDED: this request was already " + r.getStatus() + ".");
        }
        if (!approve && (note == null || note.isBlank())) {
            // Same rule as a declined join request. A refusal with no reason produces the same
            // request again next week and tells the person nothing.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "NOTE_REQUIRED: say why, even briefly — leave is usually asked for a reason.");
        }

        try {
            r.decide(approve, deciderUserId, trimTo(note, 500));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        requests.save(r);

        if (approve) {
            int written = writeAvailabilityRows(r);
            log.info("Salon {} APPROVED leave {} for stylist {} ({} to {}) — {} blocking "
                    + "availability row(s) written; they are now unbookable on those dates.",
                    salonId, requestId, r.getStylistId(), r.getStartsOn(), r.getEndsOn(), written);
        } else {
            log.info("Salon {} declined leave {} for stylist {}", salonId, requestId, r.getStylistId());
        }

        publishDecision(r, approve);
        return r;
    }

    /**
     * Tell the stylist what the salon said about their leave. Session 49.
     *
     * <h2>Why this notification matters more than most</h2>
     * Leave is the request people make plans around. A stylist who asked for four days in October
     * and hears nothing will either book the flights and not turn up, or cancel a trip they didn't
     * need to — both from the same silence.
     *
     * <p>The in-app screen already says "not approved yet, you're still on the calendar", which is
     * the right message while they're looking at it. This is for when they aren't.
     *
     * <h2>Non-fatal, and loud</h2>
     * The decision is saved and the availability rows are written — those are what actually
     * matter. Failing the whole transaction because an email couldn't be queued would leave a
     * stylist on the calendar for a day the salon has already agreed they're away, which is far
     * worse than a missing email. Logged at ERROR because from their side it looks like silence.
     */
    private void publishDecision(StylistLeaveRequest r, boolean approved) {
        String email = null;
        String name = null;
        try {
            Stylist s = stylistSelf.stylistById(r.getStylistId());
            if (s != null) {
                name = s.getName();
                if (s.getUserId() != null) {
                    var user = stylistSelf.userContact(s.getUserId());
                    if (user != null) {
                        email = user.email();
                        if (name == null || name.isBlank()) name = user.name();
                    }
                }
            }
        } catch (Exception e) {
            // Publish anyway. The dispatcher logs "no contact details", which is recoverable and
            // visible; dropping the event would leave no trace anyone was owed this.
            log.warn("Could not resolve stylist {} for the leave-decision email ({}). Publishing "
                    + "without contact details.", r.getStylistId(), e.toString());
        }

        String salonName = stylistSelf.salonName(r.getSalonId());

        try {
            outbox.publish(new com.bmp.common.events.StylistLeaveDecided(
                    r.getId(), r.getStylistId(), r.getSalonId(), salonName,
                    r.getStartsOn(), r.getEndsOn(), r.isWholeDay(),
                    approved, r.getDecisionNote(), email, name));
        } catch (Exception e) {
            log.error("Leave {} was {} but the stylist could not be told ({}). The decision and "
                    + "its calendar effect are saved — only the email is missing.",
                    r.getId(), approved ? "approved" : "declined", e.toString());
        }
    }

    /** The salon revokes leave it had approved. Same cleanup as a withdrawal. */
    @Transactional
    public StylistLeaveRequest revoke(UUID salonId, UUID requestId) {
        StylistLeaveRequest r = requests.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND"));
        if (!r.getSalonId().equals(salonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND");
        }
        return cancelInternal(r, "revoked by the salon");
    }

    // ══ the bit that actually blocks bookings ═════════════════════════════════════════════════

    /**
     * Turn an approved request into the rows the availability algorithm reads.
     *
     * <h2>One row per DAY, not one per request</h2>
     * {@code stylist_availability} is keyed on {@code specific_date} — the algorithm asks "what
     * blocks this stylist on the 14th?" and expects rows for the 14th. A single row carrying a
     * range would require the algorithm to learn about ranges, on the hot path, for the one
     * caller that needs it.
     *
     * <p>So a week's leave is seven rows. That is cheap, and it makes the leave visible in every
     * existing view of a stylist's time off without any of them changing.
     *
     * <h2>Whole day vs partial</h2>
     * A whole-day leave writes {@code start_time = end_time = null}, which
     * {@code AvailabilityService.workingWindows} treats as "not working at all that day".
     * A partial day writes the times, which {@code breakAndLeaveWindows} subtracts as an
     * interval. Both paths already existed; this just feeds them.
     *
     * @return how many rows were written — logged, because "why is this stylist still bookable?"
     *         is answered first by checking whether anything was written at all
     */
    private int writeAvailabilityRows(StylistLeaveRequest r) {
        int n = 0;
        for (LocalDate d = r.getStartsOn(); !d.isAfter(r.getEndsOn()); d = d.plusDays(1)) {
            availability.save(new StylistAvailability(
                    r.getStylistId(), r.getSalonId(),
                    "leave",
                    // day_of_week is null for date-keyed rows. It was a primitive int against a
                    // nullable column until Session 18 and threw on read; it is Integer now.
                    null,
                    d,
                    "leave",
                    r.isWholeDay() ? null : r.getStartTime().toString(),
                    r.isWholeDay() ? null : r.getEndTime().toString(),
                    true));
            n++;
        }
        return n;
    }

    /**
     * Cancel a request and remove any blocking rows it created.
     *
     * <h2>Why the cleanup has to be precise</h2>
     * Deleting "leave rows for this stylist on these dates" would also delete leave the OWNER
     * entered directly through the time-off endpoint, or rows belonging to a different approved
     * request that happens to touch the same day. Both would put a stylist back on the calendar
     * for a day they are away — the exact failure this whole feature exists to prevent.
     *
     * <p>So the delete is narrowed to rows that match this request's shape exactly: same stylist,
     * same salon, same date, same times. It is not perfect — two identical requests would be
     * indistinguishable — and the overlap check at request time is what makes that impossible.
     */
    private StylistLeaveRequest cancelInternal(StylistLeaveRequest r, String why) {
        boolean wasApproved = r.isApproved();
        try {
            r.cancel();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        requests.save(r);

        if (wasApproved) {
            int removed = 0;
            for (LocalDate d = r.getStartsOn(); !d.isAfter(r.getEndsOn()); d = d.plusDays(1)) {
                List<StylistAvailability> rows = availability
                        .findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(
                                r.getStylistId(), r.getSalonId(), "leave", d);
                for (StylistAvailability row : rows) {
                    if (sameShape(row, r)) {
                        availability.delete(row);
                        removed++;
                    }
                }
            }
            log.info("Leave {} cancelled ({}) — {} blocking row(s) removed; stylist {} is "
                    + "bookable again on {} to {}.", r.getId(), why, removed, r.getStylistId(),
                    r.getStartsOn(), r.getEndsOn());
        } else {
            log.info("Leave {} cancelled ({}) before approval — nothing to clean up.", r.getId(), why);
        }
        return r;
    }

    /**
     * Does this availability row look like one THIS request wrote?
     *
     * <p>Times are stored as strings on {@code StylistAvailability} (a {@code VARCHAR}-backed
     * TIME column read as text), so the comparison is against {@code LocalTime.toString()} —
     * exactly what {@link #writeAvailabilityRows} wrote. Comparing parsed times would be more
     * robust to formatting drift but would also silently match a row somebody wrote as
     * "09:00:00" against one written as "09:00", which are different rows in the database.
     */
    private static boolean sameShape(StylistAvailability row, StylistLeaveRequest r) {
        String wantStart = r.isWholeDay() ? null : r.getStartTime().toString();
        String wantEnd = r.isWholeDay() ? null : r.getEndTime().toString();
        return java.util.Objects.equals(row.getStartTime(), wantStart)
                && java.util.Objects.equals(row.getEndTime(), wantEnd);
    }

    // ══ validation ════════════════════════════════════════════════════════════════════════════

    private static void validateRange(LocalDate startsOn, LocalDate endsOn,
                                       LocalTime startTime, LocalTime endTime) {
        if (startsOn == null || endsOn == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DATES_REQUIRED");
        }
        if (endsOn.isBefore(startsOn)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "END_BEFORE_START: the last day of leave can't be before the first.");
        }
        /*
         * Leave in the past is refused. It cannot block anything — those days are gone — so
         * approving it would produce rows that do nothing, and a stylist would reasonably read
         * the approval as meaning something happened.
         *
         * Today IS allowed: "I'm ill, I can't come in" is the single most common leave there is,
         * and it usefully blocks the rest of today's slots.
         */
        LocalDate today = LocalDate.now(BmpTimeZone.ZONE);
        if (startsOn.isBefore(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LEAVE_IN_THE_PAST: you can ask for leave from today onwards.");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(startsOn, endsOn) + 1;
        if (days > MAX_DAYS_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LEAVE_TOO_LONG: " + days + " days in one request. Split it, or check the "
                    + "year on the end date — " + MAX_DAYS_PER_REQUEST + " days is the maximum.");
        }
        boolean oneTimeOnly = (startTime == null) != (endTime == null);
        if (oneTimeOnly) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PARTIAL_DAY_NEEDS_BOTH_TIMES: give a start and an end, or leave both empty "
                    + "for a whole day off.");
        }
        if (startTime != null && !endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "END_TIME_BEFORE_START: the leave has to end after it starts.");
        }
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
