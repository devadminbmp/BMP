package com.bmp.salon.services;

import com.bmp.salon.client.BookingServiceClient;
import com.bmp.salon.client.dto.BusyWindowsResponse;
import com.bmp.salon.dto.StylistAvailabilityDtos.*;
import com.bmp.salon.entities.StylistAvailability;
import com.bmp.salon.repositories.StylistAvailabilityRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Session 18 — the WRITE side of stylist availability.
 *
 * <p><b>The gap this closes.</b> {@code stylist_availability} has been read by the free-slot
 * algorithm since Session 8, but nothing could ever write to it. A stylist with an empty
 * template has no working windows, and no working windows means no free slots — so in practice
 * every stylist on the platform was unbookable, and the only lever anyone had was the
 * all-or-nothing {@code is_available_today} flag.
 *
 * <h2>The three kinds of rule, and when each wins</h2>
 * <ul>
 *   <li><b>weekly_template</b> — recurring hours and breaks, per weekday. The baseline.</li>
 *   <li><b>exception</b> — "different hours on this one date". REPLACES the template for that
 *       date.</li>
 *   <li><b>leave</b> — time off. Overrides everything for that date; a row with no times means
 *       the whole day.</li>
 * </ul>
 * That precedence is not invented here — it's what {@code AvailabilityService.workingWindows}
 * already implements. This class only writes rows that mean what that reader thinks they mean.
 *
 * <h2>Why this class is mostly validation</h2>
 * Availability is the single input the whole booking system trusts. A malformed row here
 * doesn't throw — it silently makes a stylist unbookable, or double-bookable, and nobody finds
 * out until a customer is standing in the salon. So every write is checked for:
 * ordering (end after start), overlap (two working windows can't collide), containment (a
 * break must sit inside a working window), and — the one that actually protects customers —
 * <b>conflicts with appointments that already exist</b>.
 */
@Service
public class StylistAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(StylistAvailabilityService.class);

    /**
     * How far ahead to look when checking whether a template change strands existing bookings.
     *
     * <p>Four weeks is a judgement call: long enough to cover how far ahead salon customers
     * actually book, short enough that changing a weekday costs a handful of calls to
     * bmp-booking rather than hundreds. A booking further out than this would slip through —
     * documented here rather than pretended away.
     */
    private static final int CONFLICT_HORIZON_DAYS = 28;

    /**
     * day_of_week convention — <b>SUNDAY = 0 … SATURDAY = 6</b>.
     *
     * <p>This is NOT arbitrary and must not be "tidied" to Monday=0: it is exactly what
     * {@code AvailabilityService.dayOfWeekIndex} uses to READ these rows, and what
     * {@code salon_hours} uses. Writing on a different convention would put every stylist's
     * hours on the wrong day — silently, with no error anywhere, until a customer couldn't book
     * on a day the salon was open.
     */
    private static int dayOfWeekIndex(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    private static final String WEEKLY = "weekly_template";
    private static final String EXCEPTION = "exception";
    private static final String LEAVE = "leave";
    private static final String WORKING = "working";
    private static final String BREAK = "break";

    private final StylistAvailabilityRepository rules;
    private final StylistSalonRepository stylistSalons;
    private final BookingServiceClient bookings;

    public StylistAvailabilityService(StylistAvailabilityRepository rules,
                                       StylistSalonRepository stylistSalons,
                                       BookingServiceClient bookings) {
        this.rules = rules;
        this.stylistSalons = stylistSalons;
        this.bookings = bookings;
    }

    // ======================================================================================
    // Reads
    // ======================================================================================

    /**
     * The weekly template, always all seven days.
     *
     * <p>Days with no rows come back with empty lists rather than being absent. A UI rendering
     * a week must not have to guess whether a missing Wednesday means "not loaded" or "doesn't
     * work Wednesdays" — and that ambiguity is exactly how someone ends up publishing a stylist
     * with no hours.
     */
    public WeeklyTemplateResponse getWeeklyTemplate(UUID salonId, UUID stylistId) {
        requireLinked(salonId, stylistId);
        Map<Integer, List<StylistAvailability>> byDay =
                rules.findByStylistIdAndSalonIdAndRuleTypeOrderByDayOfWeekAscStartTimeAsc(stylistId, salonId, WEEKLY)
                        .stream()
                        .filter(r -> r.getDayOfWeek() != null)
                        .collect(Collectors.groupingBy(StylistAvailability::getDayOfWeek));

        List<DayTemplate> days = new ArrayList<>();
        for (int d = 0; d <= 6; d++) {
            List<StylistAvailability> forDay = byDay.getOrDefault(d, List.of());
            days.add(new DayTemplate(
                    d,
                    forDay.stream().filter(r -> WORKING.equals(r.getSlotType()))
                            .map(r -> new TimeWindow(r.getStartTime(), r.getEndTime())).toList(),
                    forDay.stream().filter(r -> BREAK.equals(r.getSlotType()))
                            .map(r -> new TimeWindow(r.getStartTime(), r.getEndTime())).toList()));
        }
        return new WeeklyTemplateResponse(stylistId, salonId, days);
    }

    /** Dated rules (leave + exceptions) in a window — the "what's coming up" list. */
    public List<AvailabilityRuleResponse> listDatedRules(UUID salonId, UUID stylistId, LocalDate from, LocalDate to) {
        requireLinked(salonId, stylistId);
        return rules.findByStylistIdAndSalonIdAndSpecificDateBetweenOrderBySpecificDateAsc(stylistId, salonId, from, to)
                .stream().map(this::toResponse).toList();
    }

    // ======================================================================================
    // Writes
    // ======================================================================================

    /**
     * Replace the weekly template for the days named in the request.
     *
     * <p><b>Only the days present are touched.</b> Sending Monday and Tuesday leaves the rest of
     * the week exactly as it was — so a UI that edits one day doesn't have to re-send the whole
     * week and risk clobbering a change someone else made in the meantime. An empty
     * {@code working} list for a day is a real instruction: "doesn't work that day".
     *
     * <p>Delete-then-insert per weekday, because a day's rules are a SET; diffing individual
     * rows would be more code for identical behaviour.
     */
    @Transactional
    public WeeklyTemplateResponse replaceWeeklyTemplate(UUID salonId, UUID stylistId, WeeklyTemplateRequest req) {
        requireLinked(salonId, stylistId);
        req.days().forEach(d -> validateDay(d.working(), d.breaksOrEmpty()));

        if (!req.force()) {
            List<AvailabilityConflict> conflicts = findTemplateConflicts(salonId, stylistId, req.days());
            if (!conflicts.isEmpty()) {
                throw conflict(conflicts,
                        "This change would leave %d existing appointment(s) outside the stylist's working hours."
                                .formatted(conflicts.size()));
            }
        }

        for (DayTemplate day : req.days()) {
            rules.deleteByStylistIdAndSalonIdAndRuleTypeAndDayOfWeek(stylistId, salonId, WEEKLY, day.dayOfWeek());
            day.working().forEach(w -> rules.save(new StylistAvailability(
                    stylistId, salonId, WEEKLY, day.dayOfWeek(), null, WORKING, w.start(), w.end(), false)));
            day.breaksOrEmpty().forEach(b -> rules.save(new StylistAvailability(
                    stylistId, salonId, WEEKLY, day.dayOfWeek(), null, BREAK, b.start(), b.end(), true)));
        }

        log.info("Weekly template updated: salonId={} stylistId={} days={} force={}",
                salonId, stylistId, req.days().stream().map(DayTemplate::dayOfWeek).toList(), req.force());
        return getWeeklyTemplate(salonId, stylistId);
    }

    /**
     * Book time off on a date — a whole day, or part of one.
     *
     * <p>Written as a {@code leave} rule, which the algorithm treats as beating everything else
     * for that date. A full day is a row with null times: that null is precisely why the entity's
     * {@code dayOfWeek} had to stop being a primitive (Session 18 bugfix) — these rows carry no
     * weekday at all.
     *
     * <p>The conflict check here is the one that matters most in daily use: marking leave on a
     * day that already has appointments is the classic way a salon double-books itself.
     */
    @Transactional
    public AvailabilityRuleResponse addTimeOff(UUID salonId, UUID stylistId, TimeOffRequest req) {
        requireLinked(salonId, stylistId);
        if (!req.isFullDay()) {
            validateOrder(req.start(), req.end());
        }
        if (req.date().isBefore(LocalDate.now())) {
            // Not a hard error elsewhere, but leave in the past can only be a typo — nothing
            // downstream reads it, so accepting it would just create confusing rows.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DATE_IN_THE_PAST");
        }

        if (!req.force()) {
            List<AvailabilityConflict> conflicts = busyWithin(
                    stylistId, req.date(),
                    req.isFullDay() ? LocalTime.MIN : LocalTime.parse(req.start()),
                    req.isFullDay() ? LocalTime.MAX : LocalTime.parse(req.end()));
            if (!conflicts.isEmpty()) {
                throw conflict(conflicts,
                        "This stylist already has %d appointment(s) in that time. Move or cancel them first, or confirm to book the leave anyway."
                                .formatted(conflicts.size()));
            }
        }

        StylistAvailability saved = rules.save(new StylistAvailability(
                stylistId, salonId, LEAVE, null, req.date(), LEAVE,
                req.isFullDay() ? null : req.start(),
                req.isFullDay() ? null : req.end(),
                true));

        log.info("Time off added: salonId={} stylistId={} date={} fullDay={} force={}",
                salonId, stylistId, req.date(), req.isFullDay(), req.force());
        return toResponse(saved);
    }

    /**
     * Different hours on one specific date, without it being time off — "in late on Thursday".
     *
     * <p>Written as {@code exception} rows, which REPLACE the weekly template for that date.
     * Existing exception rows for the same date are cleared first, so setting them twice
     * doesn't silently stack two conflicting sets of hours.
     */
    @Transactional
    public List<AvailabilityRuleResponse> setDateOverride(UUID salonId, UUID stylistId, DateOverrideRequest req) {
        requireLinked(salonId, stylistId);
        validateDay(req.working(), req.breaksOrEmpty());

        if (!req.force()) {
            List<AvailabilityConflict> conflicts =
                    bookingsOutside(stylistId, req.date(), req.working(), req.breaksOrEmpty());
            if (!conflicts.isEmpty()) {
                throw conflict(conflicts,
                        "These hours would leave %d existing appointment(s) outside the stylist's working time."
                                .formatted(conflicts.size()));
            }
        }

        rules.findByStylistIdAndSalonIdAndRuleTypeAndSpecificDate(stylistId, salonId, EXCEPTION, req.date())
                .forEach(rules::delete);

        List<AvailabilityRuleResponse> created = new ArrayList<>();
        req.working().forEach(w -> created.add(toResponse(rules.save(new StylistAvailability(
                stylistId, salonId, EXCEPTION, null, req.date(), WORKING, w.start(), w.end(), false)))));
        req.breaksOrEmpty().forEach(b -> created.add(toResponse(rules.save(new StylistAvailability(
                stylistId, salonId, EXCEPTION, null, req.date(), BREAK, b.start(), b.end(), true)))));

        log.info("Date override set: salonId={} stylistId={} date={} windows={}",
                salonId, stylistId, req.date(), req.working().size());
        return created;
    }

    /** Delete one rule (cancel a leave day, drop an override). Salon-scoped lookup. */
    @Transactional
    public void deleteRule(UUID salonId, UUID ruleId) {
        StylistAvailability rule = rules.findByIdAndSalonId(ruleId, salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND_IN_THIS_SALON"));
        rules.delete(rule);
        log.info("Availability rule deleted: salonId={} ruleId={} type={}", salonId, ruleId, rule.getRuleType());
    }

    // ======================================================================================
    // Validation
    // ======================================================================================

    private void validateDay(List<TimeWindow> working, List<TimeWindow> breaks) {
        working.forEach(w -> validateOrder(w.start(), w.end()));
        breaks.forEach(b -> validateOrder(b.start(), b.end()));

        // Two working windows that overlap would double-count the stylist's time; the slot
        // algorithm would happily hand out the same minute twice.
        List<TimeWindow> sorted = working.stream()
                .sorted(Comparator.comparing(w -> LocalTime.parse(w.start()))).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (LocalTime.parse(sorted.get(i).start()).isBefore(LocalTime.parse(sorted.get(i - 1).end()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "OVERLAPPING_WORKING_HOURS: %s–%s overlaps %s–%s".formatted(
                                sorted.get(i - 1).start(), sorted.get(i - 1).end(),
                                sorted.get(i).start(), sorted.get(i).end()));
            }
        }

        // A break outside working hours blocks nothing and is almost always a typo — silently
        // accepting it means someone thinks they've booked a lunch break and hasn't.
        for (TimeWindow b : breaks) {
            LocalTime bs = LocalTime.parse(b.start());
            LocalTime be = LocalTime.parse(b.end());
            boolean inside = working.stream().anyMatch(w ->
                    !bs.isBefore(LocalTime.parse(w.start())) && !be.isAfter(LocalTime.parse(w.end())));
            if (!inside) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "BREAK_OUTSIDE_WORKING_HOURS: %s–%s isn't inside any working window".formatted(b.start(), b.end()));
            }
        }
    }

    private void validateOrder(String start, String end) {
        if (!LocalTime.parse(end).isAfter(LocalTime.parse(start))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "END_BEFORE_START: %s is not after %s".formatted(end, start));
        }
    }

    // ======================================================================================
    // Conflict detection — the part that protects customers
    // ======================================================================================

    /**
     * Does this template change strand any existing appointment?
     *
     * <p>Walks the next {@link #CONFLICT_HORIZON_DAYS} days, and for each date whose weekday is
     * being edited, asks bmp-booking what's already committed and checks it still fits inside
     * the proposed hours. Only the edited weekdays are checked, so editing Monday costs four
     * calls, not twenty-eight.
     */
    private List<AvailabilityConflict> findTemplateConflicts(UUID salonId, UUID stylistId, List<DayTemplate> days) {
        Map<Integer, DayTemplate> byWeekday = days.stream()
                .collect(Collectors.toMap(DayTemplate::dayOfWeek, d -> d, (a, b) -> b));

        List<AvailabilityConflict> conflicts = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < CONFLICT_HORIZON_DAYS; i++) {
            LocalDate date = today.plusDays(i);
            DayTemplate day = byWeekday.get(dayOfWeekIndex(date));
            if (day == null) continue;
            conflicts.addAll(bookingsOutside(stylistId, date, day.working(), day.breaksOrEmpty()));
        }
        return conflicts;
    }

    /** Committed windows on {@code date} that fall outside {@code working} or inside a break. */
    private List<AvailabilityConflict> bookingsOutside(UUID stylistId, LocalDate date,
                                                        List<TimeWindow> working, List<TimeWindow> breaks) {
        return busy(stylistId, date).stream()
                .filter(w -> {
                    boolean covered = working.stream().anyMatch(t ->
                            !w.start().isBefore(LocalTime.parse(t.start())) && !w.end().isAfter(LocalTime.parse(t.end())));
                    boolean hitsBreak = breaks.stream().anyMatch(t ->
                            w.start().isBefore(LocalTime.parse(t.end())) && w.end().isAfter(LocalTime.parse(t.start())));
                    return !covered || hitsBreak;
                })
                .map(w -> new AvailabilityConflict(date, w.start().toString(), w.end().toString(), w.source()))
                .toList();
    }

    /** Committed windows on {@code date} overlapping [from, to). */
    private List<AvailabilityConflict> busyWithin(UUID stylistId, LocalDate date, LocalTime from, LocalTime to) {
        return busy(stylistId, date).stream()
                .filter(w -> w.start().isBefore(to) && w.end().isAfter(from))
                .map(w -> new AvailabilityConflict(date, w.start().toString(), w.end().toString(), w.source()))
                .toList();
    }

    /**
     * What bmp-booking says the stylist is already committed to.
     *
     * <p>If that service is unreachable we return NOTHING — meaning the conflict check finds no
     * conflicts and the write proceeds. That's the deliberate choice: an unavailable booking
     * service must not stop a salon from fixing its own hours, and the failure is loud in the
     * logs. The opposite policy (refuse on error) would take the whole desk down with one
     * service.
     */
    private List<BusyWindowsResponse.Window> busy(UUID stylistId, LocalDate date) {
        try {
            // null excludeBookingId (Session 37): this is a stylist changing their OWN hours, so
            // every existing appointment must count as a conflict. Excluding anything here would
            // let a stylist schedule time off over a booking they already have.
            BusyWindowsResponse resp = bookings.getBusyWindows(stylistId, date, null);
            return resp == null || resp.windows() == null ? List.of() : resp.windows();
        } catch (Exception e) {
            log.error("Conflict check skipped for stylistId={} date={} — bmp-booking unreachable ({}). "
                    + "The availability write proceeds WITHOUT verifying existing appointments.",
                    stylistId, date, e.toString());
            return List.of();
        }
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    /** A stylist's availability is per salon, so the link must exist before rules can. */
    private void requireLinked(UUID salonId, UUID stylistId) {
        stylistSalons.findBySalonIdAndStylistId(salonId, stylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "STYLIST_NOT_LINKED_TO_THIS_SALON"));
    }

    private ResponseStatusException conflict(List<AvailabilityConflict> conflicts, String message) {
        // The conflicts ride in the reason so the client can surface something specific; a
        // richer body would need a @ControllerAdvice, flagged in the controller's javadoc.
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "AVAILABILITY_CONFLICT: " + message + " " + conflicts);
    }

    private AvailabilityRuleResponse toResponse(StylistAvailability r) {
        return new AvailabilityRuleResponse(r.getId(), r.getStylistId(), r.getSalonId(), r.getRuleType(),
                r.getDayOfWeek(), r.getSpecificDate(), r.getSlotType(), r.getStartTime(), r.getEndTime(),
                r.isBlocksBooking());
    }
}
