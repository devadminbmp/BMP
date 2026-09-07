package com.bmp.auth.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code OtpRequests.markConsumed} / {@code isConsumed}. Session 43.
 *
 * <h2>Why a two-line method gets its own test file</h2>
 * Because it is the entire implementation of "one-time" in one-time password.
 *
 * <p>Before V005 there was no consumption at all: a verified code kept authenticating for the
 * rest of its five-minute TTL. That bug had no symptom. Nothing failed, nothing logged, every
 * login worked — the code simply also worked for whoever else had it, and email being the only
 * live delivery channel means "whoever else" includes anyone glancing at an inbox or reading a
 * forwarded message.
 *
 * <p>That is the failure mode worth a file: <b>the bug that looks like success</b>. The same
 * argument as {@code MaskPhoneTest} in bmp-booking — a guard that silently stops guarding is
 * more dangerous than one that breaks loudly, because only the loud one gets fixed.
 *
 * <p>These tests deliberately exercise the entity directly rather than through AuthService. The
 * question here is narrow and total: <i>can a consumed code ever become unconsumed?</i> Answering
 * it needs no database, no Spring context and no mocks, so it should need none — a test that is
 * cheap to run is a test that actually runs.
 */
class OtpConsumptionTest {

    private static OtpRequests freshOtp() {
        return new OtpRequests("+919876500003", "owner.lumiere@example.com", "$2a$10$fakehash",
                0, null, Instant.now().plusSeconds(300));
    }

    @Test
    @DisplayName("a new code starts unconsumed")
    void newCodeIsUnconsumed() {
        OtpRequests otp = freshOtp();
        assertThat(otp.isConsumed()).isFalse();
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("marking consumed records a timestamp and flips isConsumed")
    void markingConsumedRecordsWhen() {
        OtpRequests otp = freshOtp();
        Instant before = Instant.now();

        otp.markConsumed();

        assertThat(otp.isConsumed()).isTrue();
        assertThat(otp.getConsumedAt()).isNotNull();
        // A timestamp, not a flag: "when" answers questions a boolean can't — chiefly how long
        // after the legitimate login a replay attempt arrived. See V005's header.
        assertThat(otp.getConsumedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("marking consumed twice does NOT move the timestamp — the first use is the real one")
    void markingTwiceKeepsTheFirstTimestamp() throws InterruptedException {
        OtpRequests otp = freshOtp();
        otp.markConsumed();
        Instant firstUse = otp.getConsumedAt();

        Thread.sleep(5); // enough for Instant.now() to differ
        otp.markConsumed();

        // Idempotent on purpose. A retried transaction must not fail on its own second pass,
        // and — more importantly — a second call must not overwrite the record of the FIRST
        // use. If it did, a replay would quietly rewrite history to look like the original
        // login, destroying the one piece of evidence that a replay happened.
        assertThat(otp.getConsumedAt()).isEqualTo(firstUse);
        assertThat(otp.isConsumed()).isTrue();
    }

    @Test
    @DisplayName("there is no way to un-consume a code")
    void thereIsNoUnconsume() {
        OtpRequests otp = freshOtp();
        otp.markConsumed();

        // This test has no assertion beyond the one below because its real subject is the
        // ABSENCE of a setter. `consumedAt` deliberately has no setConsumedAt() — the only
        // legitimate transition is unused -> used, once. A plain setter would have satisfied
        // the JavaBean convention and quietly re-opened exactly the hole V005 closed.
        //
        // If someone adds setConsumedAt(null) later, this file is where they should have to
        // argue for it.
        assertThat(OtpRequests.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("setConsumedAt");

        assertThat(otp.isConsumed()).isTrue();
    }
}
