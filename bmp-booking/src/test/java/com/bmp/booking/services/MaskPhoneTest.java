package com.bmp.booking.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code BookingService.maskPhone}. Session 39.
 *
 * <h2>Why a six-line string function gets its own test file</h2>
 * Because it is the only thing standing between a salon's day view and a downloadable list of
 * BMP's customers. It runs on every row of the schedule, every row of history, and every booking
 * response. If it ever returns the input unchanged, nothing breaks, nothing logs, no test fails —
 * the numbers simply appear, and keep appearing.
 *
 * <p>That is the failure mode worth spending a file on: the bug that looks like success.
 *
 * <p>The commercial stake, from Session 35: a salon that can harvest BMP's customers can take
 * them off-platform, which is the commission walking out the door. The legal stake: under DPDP,
 * BMP is the data fiduciary for that leak regardless of who did the exporting.
 *
 * <p>Package-private static, so this test lives in the same package rather than opening up the
 * method's visibility to be testable. Widening a method's access for a test is a small change
 * that quietly invites a caller from somewhere it shouldn't have one.
 */
class MaskPhoneTest {

    @Test
    @DisplayName("hides the last four digits of a normal Indian mobile")
    void masksTheDialableHalf() {
        // The leading digits stay: they let a manager match a walk-in against a booking, and
        // confirm a number a customer reads out. The last four are what make it dialable.
        assertThat(BookingService.maskPhone("9876543210")).isEqualTo("987654••••");
        assertThat(BookingService.maskPhone("+919876543210")).isEqualTo("+91987654••••");
    }

    @Test
    @DisplayName("the masked output can never be dialled")
    void outputIsNotDialable() {
        String masked = BookingService.maskPhone("9876543210");

        assertThat(masked).contains("••••");
        // The property that actually matters, stated directly rather than implied by an equals:
        // a refactor that changed the mask character would still have to keep this true.
        assertThat(masked).doesNotContain("3210");
    }

    @Test
    @DisplayName("null in, null out — no number is not the same as a hidden number")
    void nullPassesThrough() {
        // Callers distinguish these: a null phone means BMP holds nothing for that customer
        // (a walk-in, or a pre-V006 booking), and the UI says so instead of offering to call.
        assertThat(BookingService.maskPhone(null)).isNull();
    }

    /**
     * Anything under six digits is returned as-is.
     *
     * <p>Deliberate: masking four of five digits leaves something unrecognisable to the manager
     * while protecting nothing — a five-digit string cannot identify a person anyway. The guard
     * also stops {@code substring} throwing on short or malformed data, which is the real reason
     * it can't simply be removed.
     */
    @Test
    @DisplayName("short strings are left alone rather than mangled or throwing")
    void shortInputsAreUntouched() {
        assertThat(BookingService.maskPhone("")).isEmpty();
        assertThat(BookingService.maskPhone("12345")).isEqualTo("12345");
        // Six is the first length that gets masked — the boundary, pinned.
        assertThat(BookingService.maskPhone("123456")).isEqualTo("12••••");
    }

    @Test
    @DisplayName("masking twice doesn't mask the mask")
    void idempotentEnough() {
        // Not a real code path today, but a response that gets re-masked somewhere downstream
        // must not degrade into bullets. The bullets are not digits, so the tail stays put.
        String once = BookingService.maskPhone("9876543210");
        assertThat(BookingService.maskPhone(once)).isEqualTo("987654••••");
    }
}
