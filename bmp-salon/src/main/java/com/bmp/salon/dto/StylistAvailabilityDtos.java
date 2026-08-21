package com.bmp.salon.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Session 18 — editing a stylist's availability.
 *
 * <p>Until this pass {@code stylist_availability} was read-only: the free-slot algorithm
 * consumed a table nothing could populate, so in practice every stylist had an empty template
 * and was unbookable. These are the write shapes.
 *
 * <h2>Why times are strings</h2>
 * The column is VARCHAR (see V006/V007 — this repo moved TIME columns to varchar deliberately),
 * and the whole system speaks {@code "HH:mm"} 24-hour local salon time. Validating with a regex
 * at the boundary keeps one canonical format everywhere instead of letting each caller's
 * serialiser pick a different one.
 */
public final class StylistAvailabilityDtos {
    private StylistAvailabilityDtos() {}

    /** 24-hour "HH:mm". Rejects "9:00", "09:00:00" and "24:00" — one format, no ambiguity. */
    private static final String HHMM = "^([01]\\d|2[0-3]):[0-5]\\d$";

    public record TimeWindow(
        @NotNull @Pattern(regexp = HHMM, message = "start must be HH:mm, 24-hour") String start,
        @NotNull @Pattern(regexp = HHMM, message = "end must be HH:mm, 24-hour") String end
    ) {}

    /**
     * One weekday of the template.
     *
     * <p>An empty {@code working} list means the stylist doesn't work that day — that's how a
     * day off is expressed, not by omitting the day. Omitting a day from the request leaves it
     * untouched, which is a different thing and the source of the most likely mistake here, so
     * the two are kept distinct rather than collapsed.
     *
     * <p>{@code breaks} must sit INSIDE a working window; a break outside working hours is
     * meaningless and almost always a typo.
     */
    public record DayTemplate(
        /**
         * <b>SUNDAY = 0 … SATURDAY = 6.</b> Matches salon_hours and what the free-slot
         * algorithm reads ({@code AvailabilityService.dayOfWeekIndex}). Do not renumber:
         * a mismatch puts hours on the wrong day silently, with no error anywhere.
         */
        @NotNull @Min(0) @Max(6) Integer dayOfWeek,
        @NotNull List<@Valid TimeWindow> working,
        List<@Valid TimeWindow> breaks
    ) {
        public List<TimeWindow> breaksOrEmpty() {
            return breaks == null ? List.of() : breaks;
        }
    }

    /**
     * Replace the weekly template for the days present in {@code days}.
     *
     * <p>{@code force} skips the conflict check that refuses a change which would strand
     * existing bookings outside the new hours. It exists because sometimes the salon really has
     * decided to close early and will phone those customers — but it's opt-in, so the default
     * can't quietly break someone's appointment.
     */
    public record WeeklyTemplateRequest(
        @NotNull List<@Valid DayTemplate> days,
        boolean force
    ) {}

    /**
     * Time off on a specific date. Omit both times for a full day.
     *
     * <p>Stored as a {@code leave} rule, which the algorithm treats as overriding everything
     * else for that date — see AvailabilityService.workingWindows.
     */
    public record TimeOffRequest(
        @NotNull LocalDate date,
        @Pattern(regexp = HHMM, message = "start must be HH:mm, 24-hour") String start,
        @Pattern(regexp = HHMM, message = "end must be HH:mm, 24-hour") String end,
        boolean force
    ) {
        public boolean isFullDay() {
            return start == null || end == null;
        }
    }

    /**
     * A one-off change of hours for a single date that does NOT mean "off" — e.g. "in late on
     * Thursday". Stored as an {@code exception} rule, which REPLACES the weekly template for
     * that date only.
     */
    public record DateOverrideRequest(
        @NotNull LocalDate date,
        @NotNull List<@Valid TimeWindow> working,
        List<@Valid TimeWindow> breaks,
        boolean force
    ) {
        public List<TimeWindow> breaksOrEmpty() {
            return breaks == null ? List.of() : breaks;
        }
    }

    public record AvailabilityRuleResponse(
        UUID id, UUID stylistId, UUID salonId, String ruleType, Integer dayOfWeek,
        LocalDate specificDate, String slotType, String startTime, String endTime,
        boolean blocksBooking
    ) {}

    public record WeeklyTemplateResponse(UUID stylistId, UUID salonId, List<DayTemplate> days) {}

    /**
     * What a proposed change would break: appointments that already exist and would no longer
     * sit inside the stylist's working hours.
     *
     * <p>Returned as a 409 body rather than a bare error string so the UI can name the actual
     * appointments — "this would strand 2 bookings" is actionable, "conflict" is not.
     */
    public record AvailabilityConflict(LocalDate date, String start, String end, String source) {}

    public record ConflictResponse(String error, String message, List<AvailabilityConflict> conflicts) {}
}
