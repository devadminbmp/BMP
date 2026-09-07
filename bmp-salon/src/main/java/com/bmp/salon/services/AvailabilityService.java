package com.bmp.salon.services;

import com.bmp.salon.api.AvailabilityApi;
import com.bmp.salon.client.BookingServiceClient;
import com.bmp.salon.client.dto.BusyWindowsResponse;
import com.bmp.salon.entities.SalonHours;
import com.bmp.salon.entities.SalonPolicy;
import com.bmp.salon.entities.StylistAvailability;
import com.bmp.salon.entities.WalkInBlock;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.SalonHoursRepository;
import com.bmp.salon.repositories.SalonPolicyRepository;
import com.bmp.salon.repositories.StylistAvailabilityRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import com.bmp.salon.repositories.WalkInBlockRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.bmp.common.time.BmpTimeZone;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session 8 — the availability algorithm ({@link AvailabilityApi}'s first real
 * implementation, previously a stub per its own javadoc). Booking consumes ONLY the
 * interface; everything below is free to change without touching booking.
 *
 * <p><b>Design decisions made this session (Darshan-only — same ratification flag as every
 * other Darshan-only decision in this repo, see CONTEXT.md):</b>
 * <ul>
 *   <li><b>Q1 (slot granularity):</b> grid-aligned, per-salon (salon_policy.slot_granularity_minutes,
 *       default 15) — a bookable slot's start must land on that grid, but its required
 *       length is duration-derived (the requested service duration), not fixed.</li>
 *   <li><b>Q2 (walk-in block speed):</b> {@link #blockWalkIn} does one overlap check against
 *       the same busy-window computation freeSlots uses, then a single insert — no working-hours
 *       validation, so front-desk staff can walk-in-block a stylist slightly outside their
 *       normal hours if that's what actually happened. Deliberately NOT re-validated against
 *       stylist_availability, to keep this the "&lt;5-second" operation the interface's javadoc
 *       demands.</li>
 *   <li><b>Q3 (breaks: template vs exception):</b> both exist and stack — weekly_template rows
 *       give the recurring pattern per day_of_week; exception rows for a specific_date can
 *       either add a one-off working window (overriding the template for that date only) or
 *       add a one-off break/leave window (on top of whatever the day's working windows are).</li>
 *   <li><b>Q4 (leave with existing bookings):</b> NOT handled here — marking a stylist on
 *       leave does not touch already-CONFIRMED bookings. That's a booking_disruption /
 *       reschedule-notification concern (see booking_schema.booking_disruption), out of
 *       scope for this read-only availability query. Flagged as a real gap, not silently
 *       resolved.</li>
 *   <li><b>Q5 (salon hours vs stylist hours conflict):</b> salon hours are the outer bound —
 *       a stylist can never be bookable outside the salon's own operating hours for that
 *       day, even if their own working-hours row says otherwise.</li>
 *   <li><b>Q6 (multi-service bookings spanning slot boundaries):</b> not this method's
 *       concern — {@code durationMinutes} is the CALLER's total across however many
 *       services are being combined; this method just finds one contiguous free run of
 *       that length. Splitting a multi-service booking across stylists is booking's job.</li>
 * </ul>
 *
 * <p><b>Day-of-week convention:</b> 0=Sunday..6=Saturday ({@code date.getDayOfWeek().getValue() % 7}),
 * matching stylist_availability.day_of_week / salon_hours.day_of_week's column comments.
 * Not independently verified against how those columns were actually populated anywhere
 * else in the codebase — worth double-checking against real data before relying on this.
 */
@Service
public class AvailabilityService implements AvailabilityApi {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AvailabilityService.class);

    private final SalonHoursRepository salonHoursRepo;
    private final SalonPolicyRepository salonPolicyRepo;
    private final StylistAvailabilityRepository availabilityRepo;
    private final WalkInBlockRepository walkInRepo;
    /** V019 (Session 48) — holidays and short closures. See closureWindows. */
    private final com.bmp.salon.repositories.SalonClosureRepository closureRepo;
    /** V025 (Session 51) — a suspended stylist produces no slots, anywhere. */
    private final StylistSuspensionGuard suspensions;
    private final StylistSalonRepository stylistSalonRepo;
    private final BookingServiceClient bookingClient;

    public AvailabilityService(SalonHoursRepository salonHoursRepo, SalonPolicyRepository salonPolicyRepo,
                                StylistAvailabilityRepository availabilityRepo, WalkInBlockRepository walkInRepo,
                                com.bmp.salon.repositories.SalonClosureRepository closureRepo,
                                StylistSalonRepository stylistSalonRepo, BookingServiceClient bookingClient,
                                StylistSuspensionGuard suspensions) {
        this.suspensions = suspensions;
        this.salonHoursRepo = salonHoursRepo;
        this.salonPolicyRepo = salonPolicyRepo;
        this.availabilityRepo = availabilityRepo;
        this.walkInRepo = walkInRepo;
        this.closureRepo = closureRepo;
        this.stylistSalonRepo = stylistSalonRepo;
        this.bookingClient = bookingClient;
    }

    private record Interval(int startMin, int endMin) {
        boolean overlaps(Interval other) {
            return startMin < other.endMin && endMin > other.startMin;
        }
    }

    private static int toMinutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static int toMinutes(String hhmmss) {
        return toMinutes(LocalTime.parse(hhmmss));
    }

    private static int dayOfWeekIndex(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7; // Sunday=0..Saturday=6
    }

    @Override
    /**
     * WHY there are no slots. Session 68.
     *
     * <h2>The bug this exists to end</h2>
     * {@code freeSlots} returns an empty list for FIVE unrelated reasons, and every one of them
     * reached the user as the same sentence: <i>"Shivam is fully booked on this date."</i>
     *
     * <p>Darshan hit it on a brand-new salon with zero bookings. Both the customer page and the
     * owner's own New Booking sheet told him a stylist with an empty diary was full. The salon
     * page said "Hours not set yet" three inches away, and the booking panel did not connect the
     * two — because it could not: it was handed a list of length zero and nothing else.
     *
     * <p>This is not an edge case. A salon that has just signed up has no hours and no stylist
     * rota, so <b>every new salon on BMP hits this on its first day</b> and is told its staff are
     * fully booked. The most likely reaction is to conclude the product is broken.
     *
     * <h2>Only one of these is "fully booked"</h2>
     * Four of them are configuration the owner can fix in under a minute, if anybody tells them
     * which one. That is the entire value here: an empty list is a fact, and a fact without its
     * cause is indistinguishable from a bug.
     *
     * <p>Same lesson as Session 66's fail-closed availability, in a different place: <b>a
     * computation that produced nothing is not the same as a computation that means nothing is
     * available</b>, and the two must never render identically.
     */
    public enum NoSlotReason {
        /** There are slots. */
        OK,
        /** V025 — barred from the platform. Never explained to a customer; see the controller. */
        STYLIST_SUSPENDED,
        /** Past, or beyond the salon's booking horizon. */
        DATE_OUT_OF_RANGE,
        /** The salon has NO opening hours at all, for any day. Nobody can ever be booked. */
        SALON_HOURS_NEVER_SET,
        /** The salon has hours, but is shut on this weekday. */
        SALON_CLOSED_THIS_DAY,
        /** This stylist has no weekly rota at all — the state every new stylist starts in. */
        STYLIST_HOURS_NEVER_SET,
        /** They have a rota, but do not work this weekday. */
        STYLIST_NOT_WORKING_THIS_DAY,
        /** Approved leave, or the salon marked them off. */
        STYLIST_ON_LEAVE,
        /** Genuinely no gap long enough. The ONLY one that means "fully booked". */
        FULLY_BOOKED,
    }

    /**
     * Slots plus the reason there are none.
     *
     * <p>One computation, two shapes of answer — rather than a second "why is it empty?" method
     * that re-runs the same checks. Two copies of this ordering would drift, and the copy used for
     * the MESSAGE drifting from the copy used for the DECISION is how a screen ends up confidently
     * explaining the wrong cause.
     */
    public record SlotResult(List<Slot> slots, NoSlotReason reason) {
        public static SlotResult none(NoSlotReason why) { return new SlotResult(List.of(), why); }
    }

    /**
     * Free slots, list only.
     *
     * <p>Kept because most callers — booking validation above all — need to know WHETHER a time is
     * free and have nothing to say to a human about it. {@link #freeSlotsExplained} is for the
     * paths that render a message.
     */
    public List<Slot> freeSlots(UUID salonId, UUID stylistId, LocalDate date, int durationMinutes,
                                 UUID excludeBookingId) {
        return freeSlotsExplained(salonId, stylistId, date, durationMinutes, excludeBookingId).slots();
    }

    /** Free slots AND, when there are none, which of the five causes it was. Session 68. */
    public SlotResult freeSlotsExplained(UUID salonId, UUID stylistId, LocalDate date,
                                          int durationMinutes, UUID excludeBookingId) {
        /*
         * V025 (Session 51) — a suspended stylist is not bookable anywhere.
         *
         * Checked HERE rather than only where links are created, because suspension has to affect
         * people who ALREADY work somewhere. The link-creation guards stop a barred stylist
         * joining a new salon; without this, somebody suspended today would keep taking bookings
         * at the salon they were already on, which is precisely the case suspension exists for.
         *
         * Returning empty rather than throwing: this is a read path a customer is behind, and a
         * stylist who cannot be booked should simply have no free time — the same shape as being
         * fully booked, which every caller already handles.
         *
         * freeSlotsAnyStylist loops through this method, so it inherits the rule for free.
         */
        if (suspensions.isSuspended(stylistId)) {
            return SlotResult.none(NoSlotReason.STYLIST_SUSPENDED);
        }

        Optional<SalonPolicy> policy = salonPolicyRepo.findBySalonId(salonId);
        int granularity = policy.map(SalonPolicy::getSlotGranularityMinutes).orElse(15);

        /*
         * V022 — the booking horizon. Checked FIRST because it is the cheapest possible answer:
         * a date past the salon's window has no slots regardless of anything else, and there is
         * no point loading hours, availability rules and the remote busy-windows call to find
         * that out.
         *
         * A salon with no policy row gets the defaults (30 days, no notice) rather than being
         * treated as closed — an unconfigured salon should behave as it did before V022.
         */
        int horizonDays = policy.map(SalonPolicy::getBookingHorizonDays).orElse(30);
        LocalDate today = LocalDate.now(BmpTimeZone.ZONE);
        if (date.isBefore(today) || date.isAfter(today.plusDays(horizonDays))) {
            return SlotResult.none(NoSlotReason.DATE_OUT_OF_RANGE);
        }

        NoSlotReason salonLevel = salonLevelReason(salonId, date);
        if (salonLevel != NoSlotReason.OK) return SlotResult.none(salonLevel);

        Optional<SalonHours> hours = salonHoursRepo.findBySalonIdAndDayOfWeek(salonId, dayOfWeekIndex(date));
        // Safe after salonLevelReason returned OK — that is precisely what it proves.
        Interval salonWindow = new Interval(toMinutes(hours.get().getOpenTime()), toMinutes(hours.get().getCloseTime()));

        List<Interval> working = workingWindows(stylistId, salonId, date);
        if (working.isEmpty()) {
            /*
             * Session 68 — three different situations arrive here, and the owner's next action is
             * different for each:
             *
             *   on leave      nothing to fix; they're off, deliberately.
             *   no rota AT ALL   the state every stylist starts in. Nobody ever set their hours,
             *                    so they have been unbookable since the day they joined — and
             *                    nothing anywhere said so. This is Shivam.
             *   not this day  they have a rota; Tuesday isn't on it.
             *
             * The middle one is the trap. A salon adds a stylist, sees them on the roster with a
             * green "available today" toggle, and reasonably assumes they can be booked. The
             * toggle is honest — it means "not off sick" — but it is the only signal on the
             * screen, and it points the wrong way.
             */
            boolean onLeave = !availabilityRepo
                    .findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(stylistId, salonId, "leave", date)
                    .isEmpty();
            if (onLeave) return SlotResult.none(NoSlotReason.STYLIST_ON_LEAVE);

            boolean anyRotaAtAll = !availabilityRepo
                    .findByStylistIdAndSalonIdAndRuleTypeOrderByDayOfWeekAscStartTimeAsc(
                            stylistId, salonId, "weekly_template")
                    .isEmpty();
            return SlotResult.none(anyRotaAtAll
                    ? NoSlotReason.STYLIST_NOT_WORKING_THIS_DAY
                    : NoSlotReason.STYLIST_HOURS_NEVER_SET);
        }
        // Q5: salon hours are the outer bound.
        working = intersectAll(working, salonWindow);

        List<Interval> blocking = new ArrayList<>();
        blocking.addAll(breakAndLeaveWindows(stylistId, salonId, date));
        blocking.addAll(walkInWindows(stylistId, date));
        blocking.addAll(bookingBusyWindows(stylistId, date, excludeBookingId));
        // V019: the salon is shut. Applies to every stylist, so it is added here rather than in
        // any per-stylist source — a closure is a fact about the building, not about a person.
        blocking.addAll(closureWindows(salonId, date));

        /*
         * V022 — minimum notice, applied as a blocking window rather than a filter on the output.
         *
         * TODAY ONLY, and that matters. "Now + 90 minutes" is a wall-clock instant; on any future
         * date every slot is already past it, and subtracting it there would be a no-op that
         * still costs a comparison per slot. Expressing it as an interval from midnight to the
         * cutoff means it flows through the same subtractAll the breaks and closures use, so a
         * slot that STRADDLES the cutoff is handled correctly — it is truncated, not kept whole
         * because its start happened to be legal.
         *
         * Zero notice (the default) produces an empty interval and changes nothing.
         */
        int notice = policy.map(SalonPolicy::getMinNoticeMinutes).orElse(0);
        if (notice > 0 && date.equals(today)) {
            int cutoff = toMinutes(java.time.LocalTime.now(BmpTimeZone.ZONE)) + notice;
            blocking.add(new Interval(0, Math.min(cutoff, 24 * 60)));
        }

        List<Interval> free = subtractAll(working, blocking);
        List<Slot> slots = sliceIntoSlots(free, granularity, durationMinutes, stylistId, date);
        // The ONLY path that legitimately means "fully booked": they work today, the salon is
        // open, and everything that remains is too short or already taken.
        return slots.isEmpty()
                ? SlotResult.none(NoSlotReason.FULLY_BOOKED)
                : new SlotResult(slots, NoSlotReason.OK);
    }

    @Override
    public List<Slot> freeSlotsAnyStylist(UUID salonId, LocalDate date, int durationMinutes,
                                           UUID excludeBookingId) {
        // Flattened from the batched call below. Same answer, one cross-service round trip
        // instead of one per stylist — see salonDayAvailability for why that mattered.
        return salonDayAvailability(salonId, date, durationMinutes, excludeBookingId)
                .values().stream().flatMap(List::stream).toList();
    }

    /**
     * Every stylist's free slots for one salon-day, computed in ONE pass. Session 52.
     *
     * <h2>The lag this removes</h2>
     * {@code freeSlotsAnyStylist} used to loop over the team calling {@code freeSlots} per
     * stylist. Each of those did a policy read, an hours read, three availability reads, a
     * walk-in read, a closure read, a suspension read — and <b>a cross-service HTTP call to
     * bmp-booking</b>. A six-stylist salon therefore made six sequential round trips and roughly
     * forty queries to answer one "what's free today?".
     *
     * <p>Here the salon-wide facts — policy, opening hours, closures, the horizon — are read
     * ONCE, and every stylist's busy windows arrive in a single batched call. What remains
     * per-stylist is interval arithmetic in memory.
     *
     * <h2>It is deliberately the same arithmetic</h2>
     * Working windows, breaks, leave, walk-ins, closures and the notice cutoff are subtracted in
     * exactly the order {@code freeSlots} uses. Two code paths that answer "is this bookable?"
     * differently is how a customer books a slot the salon does not have, so this method must
     * stay a faithful batch of that one — if you change one, change both.
     *
     * @return stylist id → their free slots. Stylists with none are present with an empty list,
     *         because "Anjali is fully booked" and "Anjali does not work here" are different
     *         answers and the picker needs to show the first.
     */
    public java.util.Map<UUID, List<Slot>> salonDayAvailability(
            UUID salonId, LocalDate date, int durationMinutes, UUID excludeBookingId) {

        List<StylistSalon> team = stylistSalonRepo.findBySalonIdAndStatus(salonId, "active");
        java.util.Map<UUID, List<Slot>> out = new java.util.LinkedHashMap<>();
        if (team.isEmpty()) return out;

        // ── salon-wide facts, read ONCE ──────────────────────────────────────────────────────
        Optional<SalonPolicy> policy = salonPolicyRepo.findBySalonId(salonId);
        int granularity = policy.map(SalonPolicy::getSlotGranularityMinutes).orElse(15);
        int horizonDays = policy.map(SalonPolicy::getBookingHorizonDays).orElse(30);
        LocalDate today = LocalDate.now(BmpTimeZone.ZONE);

        // V022 — outside the window there is nothing to compute for anyone.
        if (date.isBefore(today) || date.isAfter(today.plusDays(horizonDays))) {
            for (StylistSalon link : team) out.put(link.getStylistId(), List.of());
            return out;
        }

        Optional<SalonHours> hours = salonHoursRepo.findBySalonIdAndDayOfWeek(salonId, dayOfWeekIndex(date));
        if (hours.isEmpty()) {
            // Salon closed this weekday. Everyone is empty — and that is the answer, not an error.
            for (StylistSalon link : team) out.put(link.getStylistId(), List.of());
            return out;
        }
        Interval salonWindow = new Interval(toMinutes(hours.get().getOpenTime()),
                                            toMinutes(hours.get().getCloseTime()));

        List<Interval> closures = closureWindows(salonId, date);

        // V022 — minimum notice, today only. Computed once rather than per stylist.
        List<Interval> noticeCutoff = new ArrayList<>();
        int notice = policy.map(SalonPolicy::getMinNoticeMinutes).orElse(0);
        if (notice > 0 && date.equals(today)) {
            int cutoff = toMinutes(java.time.LocalTime.now(BmpTimeZone.ZONE)) + notice;
            noticeCutoff.add(new Interval(0, Math.min(cutoff, 24 * 60)));
        }

        // ── every stylist's bookings and holds, in ONE call ──────────────────────────────────
        List<UUID> stylistIds = team.stream().map(StylistSalon::getStylistId).toList();
        java.util.Map<UUID, List<BusyWindowsResponse.Window>> busyByStylist;
        try {
            busyByStylist = bookingClient.salonBusyWindows(salonId, date, stylistIds, excludeBookingId)
                    .byStylist();
        } catch (Exception e) {
            /*
             * ══════════════════════════════════════════════════════════════════════════════════
             * SESSION 66 — THIS USED TO RETURN EMPTY, AND THAT WAS A LIE THE UI COULD NOT DETECT
             * ══════════════════════════════════════════════════════════════════════════════════
             * The old behaviour returned an empty list for every stylist and logged an error. The
             * refusal to guess "everything is free" was RIGHT and is preserved — offering slots we
             * cannot verify would double-book real customers, which is the worst outcome this
             * method has available.
             *
             * But an empty map is the same value this method returns when the salon is genuinely
             * booked solid. So the picker rendered "Nobody has a 120-minute gap", and Darshan read
             * that on a salon with ZERO bookings and a stylist working 10am-7pm. The screen was
             * confidently telling him something false, and there was no way to tell it apart from
             * the truth without reading server logs.
             *
             * A COMPUTATION THAT FAILED IS NOT A COMPUTATION THAT RETURNED ZERO. Throwing keeps
             * the fail-closed guarantee — no slots are offered — while letting the caller say "we
             * couldn't check" instead of "there's nothing". One is recoverable by retrying; the
             * other makes a salon think their day is full.
             *
             * 503 rather than 500 deliberately: this is a dependency being unavailable, it is
             * usually transient, and the status tells the client it is worth retrying.
             */
            log.error("Could not load busy windows for salon {} on {} ({}). Refusing to answer "
                    + "rather than report a false 'fully booked'.", salonId, date, e.toString(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AVAILABILITY_UNAVAILABLE: we can't check the diary right now, so we're not "
                    + "showing times rather than risk double-booking someone. Try again in a moment.");
        }

        for (StylistSalon link : team) {
            UUID stylistId = link.getStylistId();

            // V025 — a suspended stylist is not bookable anywhere.
            if (suspensions.isSuspended(stylistId)) {
                out.put(stylistId, List.of());
                continue;
            }

            List<Interval> working = workingWindows(stylistId, salonId, date);
            if (working.isEmpty()) {
                out.put(stylistId, List.of());
                continue;
            }
            working = intersectAll(working, salonWindow);

            List<Interval> blocking = new ArrayList<>();
            blocking.addAll(breakAndLeaveWindows(stylistId, salonId, date));
            blocking.addAll(walkInWindows(stylistId, date));
            blocking.addAll(busyByStylist.getOrDefault(stylistId, List.of()).stream()
                    .map(w -> new Interval(toMinutes(w.start()), toMinutes(w.end())))
                    .toList());
            blocking.addAll(closures);
            blocking.addAll(noticeCutoff);

            out.put(stylistId, sliceIntoSlots(subtractAll(working, blocking),
                    granularity, durationMinutes, stylistId, date));
        }
        return out;
    }

    @Override
    public void blockWalkIn(UUID salonId, UUID stylistId, LocalDate date, LocalTime start, int durationMinutes) {
        Interval requested = new Interval(toMinutes(start), toMinutes(start) + durationMinutes);

        List<Interval> busy = new ArrayList<>();
        busy.addAll(breakAndLeaveWindows(stylistId, salonId, date));
        busy.addAll(walkInWindows(stylistId, date));
        busy.addAll(bookingBusyWindows(stylistId, date, null));
        // A walk-in must not be placed inside a closure either — the salon is shut for staff too.
        busy.addAll(closureWindows(salonId, date));

        for (Interval b : busy) {
            if (requested.overlaps(b)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "STYLIST_NOT_FREE: requested walk-in window overlaps an existing booking, hold, break, leave, or walk-in block");
            }
        }

        walkInRepo.save(new WalkInBlock(salonId, stylistId, date, start.toString(), durationMinutes, null));
    }

    // ---- window computation helpers ----

    /**
     * The salon's closures on this date, as minute-of-day intervals. V019 (Session 48).
     *
     * <h2>Instants in, minutes-of-day out</h2>
     * Closures are stored as absolute instants because they are compared against booking times
     * across services. This algorithm works in minutes since midnight, local to the salon. The
     * conversion happens HERE, once, against {@link BmpTimeZone#ZONE} — not at the call site and
     * not in the repository, so there is exactly one place a timezone mistake could live.
     *
     * <h2>Clamping to the day</h2>
     * A closure from Friday 6pm to Monday 9am overlaps three days. Asked about Saturday it must
     * return the WHOLE day (0..1440), not a negative or wrapped range. Clamping the window to the
     * day's own bounds handles the multi-day case, the starts-before case and the ends-after case
     * with one pair of max/min calls; the alternative is four branches and a bug in the fourth.
     */
    private List<Interval> closureWindows(UUID salonId, LocalDate date) {
        java.time.ZoneId zone = com.bmp.common.time.BmpTimeZone.ZONE;
        java.time.Instant dayStart = date.atStartOfDay(zone).toInstant();
        java.time.Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();

        return closureRepo.findActiveOverlapping(salonId, dayStart, dayEnd).stream()
                .map(c -> {
                    long startMin = java.time.Duration.between(dayStart,
                            c.getStartsAt().isBefore(dayStart) ? dayStart : c.getStartsAt()).toMinutes();
                    long endMin = java.time.Duration.between(dayStart,
                            c.getEndsAt().isAfter(dayEnd) ? dayEnd : c.getEndsAt()).toMinutes();
                    return new Interval((int) startMin, (int) endMin);
                })
                // A zero-width interval blocks nothing and would only confuse subtractAll.
                .filter(i -> i.endMin() > i.startMin())
                .toList();
    }

    private List<Interval> workingWindows(UUID stylistId, UUID salonId, LocalDate date) {
        // Full/partial-day leave for this exact date takes precedence over everything else.
        List<StylistAvailability> leave = availabilityRepo.findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(
                stylistId, salonId, "leave", date);
        boolean fullDayLeave = leave.stream().anyMatch(a -> a.getStartTime() == null || a.getEndTime() == null);
        if (fullDayLeave) {
            return List.of();
        }

        // Exception "working" rows for this exact date REPLACE the weekly template for the date.
        List<StylistAvailability> exceptionWorking = availabilityRepo
                .findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(stylistId, salonId, "exception", date)
                .stream().filter(a -> "working".equals(a.getSlotType())).toList();
        if (!exceptionWorking.isEmpty()) {
            return exceptionWorking.stream()
                    .map(a -> new Interval(toMinutes(a.getStartTime()), toMinutes(a.getEndTime())))
                    .toList();
        }

        return availabilityRepo
                .findByStylistIdAndSalonIdAndRuleTypeAndDayOfWeek(stylistId, salonId, "weekly_template", dayOfWeekIndex(date))
                .stream().filter(a -> "working".equals(a.getSlotType()))
                .map(a -> new Interval(toMinutes(a.getStartTime()), toMinutes(a.getEndTime())))
                .toList();
    }

    private List<Interval> breakAndLeaveWindows(UUID stylistId, UUID salonId, LocalDate date) {
        List<Interval> result = new ArrayList<>();

        availabilityRepo.findByStylistIdAndSalonIdAndRuleTypeAndDayOfWeek(stylistId, salonId, "weekly_template", dayOfWeekIndex(date))
                .stream().filter(StylistAvailability::isBlocksBooking)
                .filter(a -> a.getStartTime() != null && a.getEndTime() != null)
                .forEach(a -> result.add(new Interval(toMinutes(a.getStartTime()), toMinutes(a.getEndTime()))));

        availabilityRepo.findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(stylistId, salonId, "exception", date)
                .stream().filter(StylistAvailability::isBlocksBooking)
                .filter(a -> a.getStartTime() != null && a.getEndTime() != null)
                .forEach(a -> result.add(new Interval(toMinutes(a.getStartTime()), toMinutes(a.getEndTime()))));

        availabilityRepo.findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(stylistId, salonId, "leave", date)
                .stream().filter(a -> a.getStartTime() != null && a.getEndTime() != null) // partial-day leave only; full-day handled in workingWindows()
                .forEach(a -> result.add(new Interval(toMinutes(a.getStartTime()), toMinutes(a.getEndTime()))));

        return result;
    }

    /**
     * The reasons that are true for the WHOLE salon, regardless of which stylist. Session 68.
     *
     * <p>Factored out because {@code salonDayAvailability} is a deliberate second copy of this
     * arithmetic ("if you change one, change both"), and two copies of the REASON logic would
     * drift exactly like two copies of the decision logic. Here the salon-level part is written
     * once and both paths call it.
     *
     * <p>Cheap: at most two queries, and it short-circuits the expensive per-stylist work for the
     * commonest failure of all — a salon that has never set its opening hours.
     */
    public NoSlotReason salonLevelReason(UUID salonId, LocalDate date) {
        Optional<SalonPolicy> policy = salonPolicyRepo.findBySalonId(salonId);
        int horizonDays = policy.map(SalonPolicy::getBookingHorizonDays).orElse(30);
        LocalDate today = LocalDate.now(BmpTimeZone.ZONE);
        if (date.isBefore(today) || date.isAfter(today.plusDays(horizonDays))) {
            return NoSlotReason.DATE_OUT_OF_RANGE;
        }
        if (salonHoursRepo.findBySalonIdAndDayOfWeek(salonId, dayOfWeekIndex(date)).isPresent()) {
            return NoSlotReason.OK;
        }
        /*
         * "Shut on Sundays" and "never told us when they open" are different problems with
         * different fixes, and this used to be one branch returning one empty list.
         *
         * The second is the state EVERY salon starts in, and it is what Darshan hit: Spin Salon
         * printed "Hours not set yet" on the same page that told him his stylist was fully
         * booked. One extra query turns a dead end into an instruction.
         */
        return salonHoursRepo.findBySalonId(salonId).isEmpty()
                ? NoSlotReason.SALON_HOURS_NEVER_SET
                : NoSlotReason.SALON_CLOSED_THIS_DAY;
    }

    /**
     * The reason as a sentence, written for whoever is reading it.
     *
     * <h2>Why the audience matters, and is not just tone</h2>
     * A CUSTOMER must never be told that a salon has not configured its rota, or that a named
     * stylist is suspended. The first is the salon's private embarrassment and the second is
     * somebody's employment status — neither is a customer's business, and both would leak
     * whichever way the salon would least like. Customers get "no times available", which is true.
     *
     * <p>The OWNER gets the actual cause and where to fix it, because for four of these the fix
     * takes under a minute and the only thing missing is knowing which screen to open.
     *
     * @param forOwner true when the reader is salon staff. Never true on a customer-facing path.
     */
    public static String explain(NoSlotReason reason, boolean forOwner, String stylistName) {
        String who = stylistName == null || stylistName.isBlank() ? "This stylist" : stylistName;
        if (!forOwner) {
            // One sentence for every cause. A customer needs to know to try another day, not why.
            return reason == NoSlotReason.FULLY_BOOKED
                    ? "No times left on this date."
                    : "No times available on this date.";
        }
        return switch (reason) {
            case OK -> "";
            case STYLIST_SUSPENDED ->
                    who + " is suspended from BMP and can't take bookings. Contact support.";
            case DATE_OUT_OF_RANGE ->
                    "That date is outside your booking window. Salon → Booking window changes how "
                    + "far ahead customers can book.";
            case SALON_HOURS_NEVER_SET ->
                    "You haven't set your salon's opening hours yet, so nobody can be booked at "
                    + "all. Set them under Salon → Hours — this is the one thing to fix first.";
            case SALON_CLOSED_THIS_DAY ->
                    "Your salon is closed on this day. Salon → Hours if that's wrong.";
            case STYLIST_HOURS_NEVER_SET ->
                    who + " has no working hours set, so they can't be booked on any day. Open "
                    + "them under People → Stylists → Working hours & time off.";
            case STYLIST_NOT_WORKING_THIS_DAY ->
                    who + " doesn't work this day. Their weekly hours are under People → Stylists.";
            case STYLIST_ON_LEAVE -> who + " is on time off for this date.";
            case FULLY_BOOKED -> who + " has no free gap long enough on this date.";
        };
    }

    private List<Interval> walkInWindows(UUID stylistId, LocalDate date) {
        return walkInRepo.findByStylistIdAndBlockDate(stylistId, date).stream()
                .map(w -> new Interval(toMinutes(w.getStartTime()), toMinutes(w.getStartTime()) + w.getDurationMinutes()))
                .toList();
    }

    /**
     * The single-stylist path to bmp-booking.
     *
     * <h2>Session 66 — why this now catches, when it previously let the raw failure through</h2>
     * It didn't fail closed and it didn't fail loudly either: the Feign exception propagated
     * untouched, so what reached the screen was
     *
     * <pre>
     *   [500] during [GET] to [http://bmp-booking-service/api/v1/bookings/internal/busy-windows
     *   ?stylistId=01a0742f-…&amp;date=06/09/2026] [BookingServiceClient#getBusyWindows(UUID,…)]:
     *   Failed to convert value of type 'java.lang.String' to required type 'java.time.LocalDate'
     * </pre>
     *
     * — an internal URL, an internal service name, a stylist id and a Spring stack trace, shown
     * to a salon owner trying to block fifteen minutes for a walk-in. That is a disclosure problem
     * as well as a usability one: internal topology should not reach an end user's screen.
     *
     * <p>Same treatment as the salon-day path above, and deliberately the same message: two
     * callers of one dependency, one story about what its absence means.
     */
    private List<Interval> bookingBusyWindows(UUID stylistId, LocalDate date, UUID excludeBookingId) {
        BusyWindowsResponse resp;
        try {
            resp = bookingClient.getBusyWindows(stylistId, date, excludeBookingId);
        } catch (ResponseStatusException e) {
            throw e;   // already ours, already carries a usable message
        } catch (Exception e) {
            log.error("Could not load busy windows for stylist {} on {} ({}). Refusing to compute "
                    + "availability rather than risk double-booking.", stylistId, date, e.toString(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AVAILABILITY_UNAVAILABLE: we can't check the diary right now, so we're not "
                    + "showing times rather than risk double-booking someone. Try again in a moment.");
        }
        return resp.windows().stream()
                .map(w -> new Interval(toMinutes(w.start()), toMinutes(w.end())))
                .toList();
    }

    // ---- interval math ----

    private static List<Interval> intersectAll(List<Interval> windows, Interval bound) {
        List<Interval> result = new ArrayList<>();
        for (Interval w : windows) {
            int start = Math.max(w.startMin(), bound.startMin());
            int end = Math.min(w.endMin(), bound.endMin());
            if (start < end) {
                result.add(new Interval(start, end));
            }
        }
        return result;
    }

    /** Subtracts every interval in {@code blocking} from every interval in {@code working},
     * merging overlaps in blocking first so subtraction is correct regardless of input order. */
    private static List<Interval> subtractAll(List<Interval> working, List<Interval> blocking) {
        List<Interval> merged = mergeOverlapping(blocking);
        List<Interval> result = new ArrayList<>(working);
        for (Interval b : merged) {
            List<Interval> next = new ArrayList<>();
            for (Interval w : result) {
                if (!w.overlaps(b)) {
                    next.add(w);
                    continue;
                }
                if (w.startMin() < b.startMin()) {
                    next.add(new Interval(w.startMin(), b.startMin()));
                }
                if (w.endMin() > b.endMin()) {
                    next.add(new Interval(b.endMin(), w.endMin()));
                }
            }
            result = next;
        }
        return result;
    }

    private static List<Interval> mergeOverlapping(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort((a, b) -> Integer.compare(a.startMin(), b.startMin()));
        List<Interval> merged = new ArrayList<>();
        for (Interval i : sorted) {
            if (!merged.isEmpty() && i.startMin() <= merged.get(merged.size() - 1).endMin()) {
                Interval last = merged.remove(merged.size() - 1);
                merged.add(new Interval(last.startMin(), Math.max(last.endMin(), i.endMin())));
            } else {
                merged.add(i);
            }
        }
        return merged;
    }

    private static List<Slot> sliceIntoSlots(List<Interval> free, int granularity, int durationMinutes,
                                              UUID stylistId, LocalDate date) {
        List<Slot> slots = new ArrayList<>();
        for (Interval interval : free) {
            // grid-align the start upward to the next multiple of `granularity`
            int gridStart = ((interval.startMin() + granularity - 1) / granularity) * granularity;
            for (int start = gridStart; start + durationMinutes <= interval.endMin(); start += granularity) {
                slots.add(new Slot(minutesToTime(start), minutesToTime(start + durationMinutes), stylistId));
            }
        }
        return slots;
    }

    private static LocalTime minutesToTime(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }
}
