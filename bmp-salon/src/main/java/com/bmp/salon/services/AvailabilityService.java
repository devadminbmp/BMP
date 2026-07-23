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

    private final SalonHoursRepository salonHoursRepo;
    private final SalonPolicyRepository salonPolicyRepo;
    private final StylistAvailabilityRepository availabilityRepo;
    private final WalkInBlockRepository walkInRepo;
    private final StylistSalonRepository stylistSalonRepo;
    private final BookingServiceClient bookingClient;

    public AvailabilityService(SalonHoursRepository salonHoursRepo, SalonPolicyRepository salonPolicyRepo,
                                StylistAvailabilityRepository availabilityRepo, WalkInBlockRepository walkInRepo,
                                StylistSalonRepository stylistSalonRepo, BookingServiceClient bookingClient) {
        this.salonHoursRepo = salonHoursRepo;
        this.salonPolicyRepo = salonPolicyRepo;
        this.availabilityRepo = availabilityRepo;
        this.walkInRepo = walkInRepo;
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
    public List<Slot> freeSlots(UUID salonId, UUID stylistId, LocalDate date, int durationMinutes) {
        int granularity = salonPolicyRepo.findBySalonId(salonId)
                .map(SalonPolicy::getSlotGranularityMinutes)
                .orElse(15);

        Optional<SalonHours> hours = salonHoursRepo.findBySalonIdAndDayOfWeek(salonId, dayOfWeekIndex(date));
        if (hours.isEmpty()) {
            return List.of(); // salon closed this day of week
        }
        Interval salonWindow = new Interval(toMinutes(hours.get().getOpenTime()), toMinutes(hours.get().getCloseTime()));

        List<Interval> working = workingWindows(stylistId, salonId, date);
        if (working.isEmpty()) {
            return List.of(); // stylist not scheduled to work this day at all
        }
        // Q5: salon hours are the outer bound.
        working = intersectAll(working, salonWindow);

        List<Interval> blocking = new ArrayList<>();
        blocking.addAll(breakAndLeaveWindows(stylistId, salonId, date));
        blocking.addAll(walkInWindows(stylistId, date));
        blocking.addAll(bookingBusyWindows(stylistId, date));

        List<Interval> free = subtractAll(working, blocking);
        return sliceIntoSlots(free, granularity, durationMinutes, stylistId, date);
    }

    @Override
    public List<Slot> freeSlotsAnyStylist(UUID salonId, LocalDate date, int durationMinutes) {
        List<Slot> all = new ArrayList<>();
        for (StylistSalon link : stylistSalonRepo.findBySalonIdAndStatus(salonId, "active")) {
            all.addAll(freeSlots(salonId, link.getStylistId(), date, durationMinutes));
        }
        return all;
    }

    @Override
    public void blockWalkIn(UUID salonId, UUID stylistId, LocalDate date, LocalTime start, int durationMinutes) {
        Interval requested = new Interval(toMinutes(start), toMinutes(start) + durationMinutes);

        List<Interval> busy = new ArrayList<>();
        busy.addAll(breakAndLeaveWindows(stylistId, salonId, date));
        busy.addAll(walkInWindows(stylistId, date));
        busy.addAll(bookingBusyWindows(stylistId, date));

        for (Interval b : busy) {
            if (requested.overlaps(b)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "STYLIST_NOT_FREE: requested walk-in window overlaps an existing booking, hold, break, leave, or walk-in block");
            }
        }

        walkInRepo.save(new WalkInBlock(salonId, stylistId, date, start.toString(), durationMinutes, null));
    }

    // ---- window computation helpers ----

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

    private List<Interval> walkInWindows(UUID stylistId, LocalDate date) {
        return walkInRepo.findByStylistIdAndBlockDate(stylistId, date).stream()
                .map(w -> new Interval(toMinutes(w.getStartTime()), toMinutes(w.getStartTime()) + w.getDurationMinutes()))
                .toList();
    }

    private List<Interval> bookingBusyWindows(UUID stylistId, LocalDate date) {
        BusyWindowsResponse resp = bookingClient.getBusyWindows(stylistId, date);
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
