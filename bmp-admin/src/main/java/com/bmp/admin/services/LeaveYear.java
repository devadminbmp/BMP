package com.bmp.admin.services;

import java.time.LocalDate;

/**
 * The leave year: 1 April to 31 March. Session 65.
 *
 * <p>Darshan's call. The Indian financial year, so leave balances reset alongside payroll and
 * appraisals rather than in the middle of them.
 *
 * <h2>Identified by its START year, everywhere</h2>
 * FY 2026-27 is {@code 2026}. One integer, one interpretation.
 *
 * <p>The alternative — storing a start and end date on every entitlement row — sounds more explicit
 * and is worse: two rows can then overlap, or leave a gap, and "which entitlement applies on
 * 31 March" becomes a range query with a boundary to get wrong in each of the several places that
 * ask it. Here the boundary is computed by one method that everything else calls.
 *
 * <h2>The 1-April boundary is the whole trick</h2>
 * A date in January 2027 belongs to FY <b>2026</b>, not 2027. That is the off-by-one that a
 * hand-written {@code date.getYear()} produces, it is wrong for three months of every year, and
 * the symptom is somebody's January leave being deducted from a balance they have not been granted
 * yet. This class exists so that comparison is written once.
 */
public final class LeaveYear {

    private LeaveYear() {}

    /** April. The month the leave year turns over. */
    public static final int START_MONTH = 4;

    /**
     * Which financial year a date falls in, as its start year.
     *
     * <pre>
     *   2026-03-31  →  2025   (still last year, right up to the last day)
     *   2026-04-01  →  2026   (first day of the new one)
     *   2027-01-15  →  2026   (January belongs to the year that began the previous April)
     * </pre>
     */
    public static int of(LocalDate date) {
        return date.getMonthValue() >= START_MONTH ? date.getYear() : date.getYear() - 1;
    }

    /** The financial year we are in right now. */
    public static int current() {
        return of(LocalDate.now());
    }

    /** First day of a financial year — 1 April of its start year. */
    public static LocalDate startOf(int fyStartYear) {
        return LocalDate.of(fyStartYear, START_MONTH, 1);
    }

    /**
     * Last day — 31 March of the following year.
     *
     * <p>Computed as "the day before the next year starts" rather than hardcoding the 31st, so it
     * cannot be wrong if the boundary month is ever changed.
     */
    public static LocalDate endOf(int fyStartYear) {
        return startOf(fyStartYear + 1).minusDays(1);
    }

    /** "2026-27" — how a year is written on a screen. Never how it is stored. */
    public static String label(int fyStartYear) {
        return fyStartYear + "-" + String.format("%02d", (fyStartYear + 1) % 100);
    }
}
