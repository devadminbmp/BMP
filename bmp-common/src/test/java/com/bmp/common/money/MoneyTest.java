package com.bmp.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Money}. Session 39 — the first test in bmp-common.
 *
 * <h2>Why a four-line class needs tests</h2>
 * Because every rupee on the platform passes through it. {@code percentBps} computes commission
 * (a salon's income), cancellation fees (a customer's charge) and, once payments exist, the
 * settlement figure. Its own javadoc calls it <i>"the ONE rounding rule used everywhere so
 * payment, payout and ledger always agree to the paisa"</i> — a claim that is either enforced or
 * decorative, and until now it was decorative.
 *
 * <p>The specific risk is not that someone breaks it deliberately. It is that someone writes
 * {@code amount * bps / 10000} inline somewhere because it looks equivalent. It isn't: that
 * truncates where this rounds half-up, and the two disagree on roughly half of all real amounts.
 * These tests pin the behaviour so a second implementation is visibly different rather than
 * quietly different.
 */
class MoneyTest {

    @Test
    @DisplayName("rupee and paise constructors agree")
    void construction() {
        assertThat(Money.ofRupees(299).paise()).isEqualTo(29_900L);
        assertThat(Money.ofPaise(29_900).paise()).isEqualTo(29_900L);
        assertThat(Money.ZERO.isZero()).isTrue();
    }

    @Test
    @DisplayName("a clean percentage is exact")
    void exactPercentage() {
        // 12% commission on ₹1,000
        assertThat(Money.ofRupees(1000).percentBps(1200).paise()).isEqualTo(12_000L);
        // 50% cancellation fee on ₹2,000
        assertThat(Money.ofRupees(2000).percentBps(5000).paise()).isEqualTo(100_000L);
    }

    /**
     * HALF-UP, not truncation, and this is the whole reason the method exists.
     *
     * <p>₹10.01 at 33.33% is 333.6333 paise. Truncating gives 333; rounding half-up gives 334.
     * One paisa, on one booking, is nothing. The same one paisa lost on every booking, in the
     * same direction, is a reconciliation that never balances — and the person who eventually
     * has to explain the drift is the one who didn't introduce it.
     */
    @Test
    @DisplayName("rounds half-up to the nearest paisa, never truncates")
    void roundsHalfUp() {
        assertThat(Money.ofPaise(1001).percentBps(3333).paise()).isEqualTo(334L);

        // Exactly .5 of a paisa must round UP, not to even.
        // 100 paise at 1250 bps = 12.5 paise -> 13.
        assertThat(Money.ofPaise(100).percentBps(1250).paise()).isEqualTo(13L);

        // Just under .5 rounds down.
        // 100 paise at 1249 bps = 12.49 -> 12.
        assertThat(Money.ofPaise(100).percentBps(1249).paise()).isEqualTo(12L);
    }

    @Test
    @DisplayName("0% and 100% are the identities you'd expect")
    void edgeRates() {
        Money amount = Money.ofRupees(1500);
        assertThat(amount.percentBps(0).paise()).isZero();
        assertThat(amount.percentBps(10_000).paise()).isEqualTo(amount.paise());
    }

    @Test
    @DisplayName("a percentage of nothing is nothing")
    void zeroAmount() {
        assertThat(Money.ZERO.percentBps(5000).paise()).isZero();
    }

    @Test
    @DisplayName("plus and minus are exact long arithmetic")
    void arithmetic() {
        assertThat(Money.ofRupees(100).plus(Money.ofRupees(250)).paise()).isEqualTo(35_000L);
        assertThat(Money.ofRupees(100).minus(Money.ofRupees(250)).paise()).isEqualTo(-15_000L);
        assertThat(Money.ofRupees(100).minus(Money.ofRupees(250)).isNegative()).isTrue();
    }

    /**
     * Overflow throws rather than wrapping.
     *
     * <p>{@code Math.multiplyExact} is deliberate: a silently wrapped total would produce a
     * negative price on a booking, which is a refund the platform never agreed to. Unreachable
     * with real salon prices — and that is exactly when a wrap-around goes unnoticed for years.
     */
    @Test
    @DisplayName("overflow is loud, not silent")
    void overflowThrows() {
        assertThatThrownBy(() -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofRupees(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("display formats to two decimals and is never parsed back")
    void display() {
        assertThat(Money.ofPaise(29_900).display()).isEqualTo("₹299.00");
        assertThat(Money.ofPaise(5).display()).isEqualTo("₹0.05");
    }

    @Test
    @DisplayName("comparison works on the underlying paise")
    void comparison() {
        assertThat(Money.ofRupees(500).gte(Money.ofRupees(500))).isTrue();
        assertThat(Money.ofRupees(499).gte(Money.ofRupees(500))).isFalse();
        assertThat(Money.ofRupees(100)).isLessThan(Money.ofRupees(200));
    }
}
