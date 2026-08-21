package com.bmp.booking.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first real test in this repository. Session 39.
 *
 * <h2>Why this class, first</h2>
 * Every other test could be written later. This one decides <b>what a customer is charged</b>,
 * from terms frozen months earlier, at a moment nobody can reproduce afterwards. If it is wrong
 * the failure is silent, arrives as money, and is argued about with a real person.
 *
 * <p>It is also the easiest thing here to test properly: pure static methods, no Spring, no
 * database, no clock injection needed beyond passing an {@code Instant}. It runs in
 * milliseconds, which means it can run on every push — unlike the {@code @SpringBootTest}
 * context-load tests, which need Postgres and are why CI has said {@code -DskipTests} since
 * Session 33.
 *
 * <h2>What these tests are actually protecting</h2>
 * Not the arithmetic — the arithmetic is four lines. They protect the <b>decisions</b>:
 * <ul>
 *   <li>a salon cancellation is free <em>before</em> any policy is consulted</li>
 *   <li>an unreadable snapshot charges nothing, rather than guessing</li>
 *   <li>the clock runs on the ORIGINAL appointment, closing the reschedule loophole</li>
 *   <li>band boundaries are inclusive at the generous end</li>
 * </ul>
 * Each of those is a sentence someone could "simplify" away in a refactor without noticing.
 */
class CancellationTermsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TOTAL = 200_000L; // ₹2,000 in paise
    private static final String REF = "BMP-2026-00001";

    /** Hours from now, as an Instant — the shape the production caller passes. */
    private static Instant inHours(long hours) {
        return Instant.now().plus(Duration.ofHours(hours));
    }

    /**
     * A snapshot with the standard shape. Written as a literal rather than built from
     * {@code snapshotPolicy()} on purpose: these tests must fail if the JSON KEYS change, because
     * a renamed key means every booking already in the database stops being readable.
     */
    private static String snapshot(int freeHours, int lateHours, int lateBps, int noNoticeBps) {
        return """
                {
                  "source": "salon_policy",
                  "freeCancelHours": %d,
                  "lateCancelHours": %d,
                  "lateCancelFeeBps": %d,
                  "noNoticeFeeBps": %d,
                  "rescheduleKeepsOriginalClock": true
                }
                """.formatted(freeHours, lateHours, lateBps, noNoticeBps);
    }

    /** 24h free, 2h inner boundary, 50% late, 100% no-notice — the shape a real salon uses. */
    private static String standard() {
        return snapshot(24, 2, 5000, 10000);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Fee bands")
    class Bands {

        @Test
        @DisplayName("well inside the free window: no charge")
        void freeWindow() {
            var d = CancellationTerms.forCancellation(
                    standard(), MAPPER, inHours(72), inHours(72), TOTAL, false, REF);

            assertThat(d.reason()).isEqualTo(CancellationTerms.FREE);
            assertThat(d.feePaise()).isZero();
            assertThat(d.isFree()).isTrue();
        }

        @Test
        @DisplayName("between the two boundaries: the late rate")
        void lateBand() {
            var d = CancellationTerms.forCancellation(
                    standard(), MAPPER, inHours(6), inHours(6), TOTAL, false, REF);

            assertThat(d.reason()).isEqualTo(CancellationTerms.LATE);
            assertThat(d.feeBps()).isEqualTo(5000);
            assertThat(d.feePaise()).isEqualTo(100_000L); // 50% of ₹2,000
        }

        @Test
        @DisplayName("under the inner boundary: the no-notice rate")
        void noNoticeBand() {
            var d = CancellationTerms.forCancellation(
                    standard(), MAPPER, inHours(1), inHours(1), TOTAL, false, REF);

            assertThat(d.reason()).isEqualTo(CancellationTerms.NO_NOTICE);
            assertThat(d.feePaise()).isEqualTo(TOTAL);
        }

        /**
         * The appointment has already started or passed.
         *
         * <p>{@code hoursUntil} is negative here, which falls through to the strictest band. That
         * is correct and worth pinning: cancelling after your slot began is the least notice
         * possible, and an unguarded comparison could easily send it the other way.
         */
        @Test
        @DisplayName("after the appointment has passed: strictest band, not free")
        void inThePast() {
            var d = CancellationTerms.forCancellation(
                    standard(), MAPPER, inHours(-3), inHours(-3), TOTAL, false, REF);

            assertThat(d.reason()).isEqualTo(CancellationTerms.NO_NOTICE);
            assertThat(d.hoursUntil()).isNegative();
            assertThat(d.feePaise()).isEqualTo(TOTAL);
        }

        /**
         * Boundaries are {@code >=}, i.e. inclusive at the generous end.
         *
         * <p>Exactly 24 hours out is FREE, not late. A customer who set a reminder for "one day
         * before" should not be charged for being punctual, and off-by-one here is the single
         * most likely bug in the whole method.
         */
        @Test
        @DisplayName("exactly on the free boundary is free, not late")
        void boundaryIsInclusive() {
            // +1 minute of slack: Duration.toHours() truncates, so a hair under 24h reads as 23.
            var d = CancellationTerms.forCancellation(
                    standard(), MAPPER, inHours(24).plusSeconds(60), inHours(24).plusSeconds(60),
                    TOTAL, false, REF);

            assertThat(d.reason()).isEqualTo(CancellationTerms.FREE);
        }

        /**
         * A salon can set a late fee of zero and still be in the LATE band.
         *
         * <p>This is why {@code reason} exists as a separate field rather than being derived from
         * the bps: "you cancelled in good time" and "this is late, but we don't charge" are
         * different things to say to a customer, and both produce {@code feePaise == 0}.
         */
        @Test
        @DisplayName("a zero late fee still reports LATE, not FREE")
        void zeroFeeIsNotTheSameAsFree() {
            var d = CancellationTerms.forCancellation(
                    snapshot(24, 2, 0, 0), MAPPER, inHours(6), inHours(6), TOTAL, false, REF);

            assertThat(d.feePaise()).isZero();
            assertThat(d.reason()).isEqualTo(CancellationTerms.LATE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Rules that must survive any refactor")
    class Invariants {

        /**
         * A salon-caused cancellation is free <b>whatever the policy says</b>.
         *
         * <p>Deliberately tested with the harshest possible policy and the worst possible timing:
         * 100% fee, cancelled after the appointment should have started. If the salon-check is
         * ever moved below the band logic, this is the test that catches it.
         *
         * <p>Charging a customer because the salon's stylist quit would be indefensible, and a
         * platform that did it once would deserve the review it got.
         */
        @Test
        @DisplayName("salon cancellation is free even at 100% and zero notice")
        void salonCancellationIsAlwaysFree() {
            var d = CancellationTerms.forCancellation(
                    snapshot(24, 2, 10000, 10000), MAPPER, inHours(-1), inHours(-1),
                    TOTAL, true, REF);

            assertThat(d.feePaise()).isZero();
            assertThat(d.feeBps()).isZero();
            assertThat(d.reason()).isEqualTo(CancellationTerms.SALON_CANCELLED);
        }

        /**
         * If we cannot read what the customer agreed to, we cannot claim they agreed to pay.
         *
         * <p>Three ways a snapshot can be useless — null, the literal {@code "{}"} that
         * pre-Session-30 bookings carry, and malformed JSON. All three must charge nothing. The
         * error belongs in the log, not on the bill.
         */
        @Test
        @DisplayName("an unreadable snapshot charges nothing")
        void unreadableSnapshotIsFree() {
            for (String bad : new String[] { null, "", "{}", "{not json" }) {
                var d = CancellationTerms.forCancellation(
                        bad, MAPPER, inHours(1), inHours(1), TOTAL, false, REF);

                assertThat(d.feePaise())
                        .as("snapshot %s must not produce a charge", bad)
                        .isZero();
                assertThat(d.reason()).isEqualTo(CancellationTerms.NO_POLICY);
            }
        }

        /**
         * THE LOOPHOLE. The scenario, concretely:
         *
         * <ol>
         *   <li>Booked Saturday 11:00. Free-cancel window 24 hours.</li>
         *   <li>At 10:00 on the day — one hour out, deep in the fee window — rescheduled to next
         *       month.</li>
         *   <li>Cancel. If the clock followed the NEW time, this is "three weeks' notice": free.</li>
         * </ol>
         *
         * The salon lost Saturday's slot with an hour's notice and was paid nothing. With
         * {@code rescheduleKeepsOriginalClock}, the fee is measured against the original 11:00
         * and the no-notice rate applies.
         */
        @Test
        @DisplayName("the clock follows the ORIGINAL appointment, not the rescheduled one")
        void rescheduleDoesNotBuyBackAFreeCancellation() {
            Instant originalSoon = inHours(1);        // where it was really booked
            Instant movedFarAway = inHours(24 * 30);  // where it was pushed to

            var d = CancellationTerms.forCancellation(
                    standard(), MAPPER, originalSoon, movedFarAway, TOTAL, false, REF);

            assertThat(d.reason())
                    .as("measuring against the rescheduled time would make this free")
                    .isEqualTo(CancellationTerms.NO_NOTICE);
            assertThat(d.feePaise()).isEqualTo(TOTAL);
        }

        /** A salon that opts out is choosing to be more generous. It must actually work. */
        @Test
        @DisplayName("a salon can opt out and have the clock follow the new time")
        void salonCanOptOutOfTheOriginalClock() {
            String optedOut = """
                    {
                      "freeCancelHours": 24, "lateCancelHours": 2,
                      "lateCancelFeeBps": 5000, "noNoticeFeeBps": 10000,
                      "rescheduleKeepsOriginalClock": false
                    }
                    """;

            var d = CancellationTerms.forCancellation(
                    optedOut, MAPPER, inHours(1), inHours(24 * 30), TOTAL, false, REF);

            assertThat(d.reason()).isEqualTo(CancellationTerms.FREE);
        }

        /**
         * A snapshot written before Session 37 has none of the V010 keys.
         *
         * <p>Those bookings must behave EXACTLY as they did before the feature existed — which
         * means charging nothing, however late the cancellation. Retroactively charging someone
         * under terms that did not exist when they booked is the specific thing
         * {@code policy_snapshot} exists to prevent.
         */
        @Test
        @DisplayName("a pre-Session-37 snapshot charges nothing")
        void oldSnapshotsAreUnaffected() {
            String old = """
                    {"source":"salon_policy","freeCancelHours":24,"lateGraceMinutes":15}
                    """;

            var d = CancellationTerms.forCancellation(
                    old, MAPPER, inHours(1), inHours(1), TOTAL, false, REF);

            assertThat(d.feePaise()).isZero();
            // The band is still reported honestly — it WAS a late cancellation, it just costs
            // nothing under the terms this booking was made under.
            assertThat(d.reason()).isEqualTo(CancellationTerms.NO_NOTICE);
        }

        /** Rounding is half-up via Money.percentBps — never re-implemented locally. */
        @Test
        @DisplayName("a fee on an odd amount rounds to the paisa, half-up")
        void roundingIsExact() {
            // 33.33% of ₹10.01 = 333.63... paise -> 334
            var d = CancellationTerms.forCancellation(
                    snapshot(24, 2, 3333, 3333), MAPPER, inHours(6), inHours(6), 1001L, false, REF);

            assertThat(d.feePaise()).isEqualTo(334L);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Whether a customer may still reschedule")
    class Reschedule {

        private static String policy(int noticeHours, int max) {
            return """
                    {"rescheduleNoticeHours": %d, "maxReschedulesPerBooking": %d}
                    """.formatted(noticeHours, max);
        }

        @Test
        @DisplayName("enough notice and allowance left: allowed")
        void allowed() {
            var c = CancellationTerms.forCustomerReschedule(
                    policy(24, 2), MAPPER, inHours(48), 0, REF);

            assertThat(c.allowed()).isTrue();
            assertThat(c.refusal()).isNull();
        }

        @Test
        @DisplayName("too close to the appointment: refused, and the message says why")
        void tooLate() {
            var c = CancellationTerms.forCustomerReschedule(
                    policy(24, 2), MAPPER, inHours(3), 0, REF);

            assertThat(c.allowed()).isFalse();
            // The refusal is shown to a customer verbatim. It must name the salon's actual number
            // and point at the alternative, or it just produces a support ticket.
            assertThat(c.refusal()).contains("24 hours").contains("cancel");
        }

        @Test
        @DisplayName("allowance used up: refused")
        void limitReached() {
            var c = CancellationTerms.forCustomerReschedule(
                    policy(24, 2), MAPPER, inHours(48), 2, REF);

            assertThat(c.allowed()).isFalse();
            assertThat(c.refusal()).contains("2 times");
        }

        /** 0 is a real setting, not "unlimited" — some salons prefer cancel-and-rebook. */
        @Test
        @DisplayName("a max of zero disables rescheduling entirely")
        void zeroMaxDisables() {
            var c = CancellationTerms.forCustomerReschedule(
                    policy(24, 0), MAPPER, inHours(500), 0, REF);

            assertThat(c.allowed()).isFalse();
        }

        /**
         * The limit check runs BEFORE the notice check.
         *
         * <p>With both violated, the customer should be told the one they cannot fix. Waiting is
         * useless when the allowance is gone; telling them "you need more notice" would send them
         * to try again tomorrow and fail again.
         */
        @Test
        @DisplayName("when both limits fail, the unfixable one is reported")
        void limitBeatsNotice() {
            var c = CancellationTerms.forCustomerReschedule(
                    policy(24, 1), MAPPER, inHours(1), 1, REF);

            assertThat(c.refusal()).contains("already changed");
        }

        /** A snapshot with no reschedule keys falls back to the platform defaults, not to "no". */
        @Test
        @DisplayName("a snapshot missing the keys uses platform defaults")
        void missingKeysUseDefaults() {
            var c = CancellationTerms.forCustomerReschedule(
                    "{\"freeCancelHours\":24}", MAPPER, inHours(48), 0, REF);

            assertThat(c.allowed()).isTrue();
            assertThat(c.max()).isEqualTo(2);
            assertThat(c.noticeHours()).isEqualTo(24);
        }
    }
}
