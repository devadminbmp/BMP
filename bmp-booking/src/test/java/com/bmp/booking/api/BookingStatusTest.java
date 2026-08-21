package com.bmp.booking.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.bmp.booking.api.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The booking state machine. Session 39.
 *
 * <h2>Why this deserves a test even though it's "just a map"</h2>
 * Its own javadoc says <i>"any status change outside this table is a bug, full stop"</i> — which
 * makes the table the specification, and an untested specification is a wish. Every entry
 * encodes a product decision that someone will eventually be tempted to loosen "just for this
 * one case", and the loosening will look harmless in a diff.
 *
 * <p>The negative cases matter more than the positive ones here. Anyone adding a transition will
 * naturally test that it works; nobody thinks to check that the things which must stay
 * impossible are still impossible.
 *
 * <p>Pure enum logic — no Spring, no database, runs in microseconds.
 */
class BookingStatusTest {

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // The moves that must work
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the happy path: PENDING → CONFIRMED → ARRIVED → IN_SERVICE → COMPLETED")
    void happyPath() {
        assertThat(PENDING.canTransitionTo(CONFIRMED, Actor.SYSTEM)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(ARRIVED, Actor.SALON)).isTrue();
        assertThat(ARRIVED.canTransitionTo(IN_SERVICE, Actor.SALON)).isTrue();
        assertThat(IN_SERVICE.canTransitionTo(COMPLETED, Actor.SALON)).isTrue();
    }

    @Test
    @DisplayName("a customer can cancel from PENDING and from CONFIRMED")
    void customerCanCancel() {
        assertThat(PENDING.canTransitionTo(CANCELLED, Actor.CUSTOMER)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(CANCELLED, Actor.CUSTOMER)).isTrue();
    }

    /**
     * Session 37 added this, and it was previously refused.
     *
     * <p>The old reasoning — "the salon cancels on the customer's behalf is not a modelled move"
     * — holds for a salon cancelling as a favour, and not at all for a burst pipe or a stylist
     * who quits on Friday. Refusing to model it didn't stop it happening; it meant the salon rang
     * the customer while BMP's database still showed a live booking, which then became a no-show
     * against someone who had done nothing wrong.
     */
    @Test
    @DisplayName("Session 37: a salon can cancel from PENDING and CONFIRMED")
    void salonCanCancel() {
        assertThat(PENDING.canTransitionTo(CANCELLED, Actor.SALON)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(CANCELLED, Actor.SALON)).isTrue();
    }

    @Test
    @DisplayName("the system can cancel a PENDING booking when payment fails")
    void systemCanCancelPending() {
        assertThat(PENDING.canTransitionTo(CANCELLED, Actor.SYSTEM)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // The moves that must stay impossible
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * ONLY the Razorpay webhook confirms a booking.
     *
     * <p>A manual salon-side confirm would create a second path into CONFIRMED that must not
     * disagree with the webhook once it exists — and the two would disagree, because one of them
     * would be a human pressing a button before the money arrived. This is the most tempting
     * shortcut in the machine, because today nothing ever reaches CONFIRMED at all.
     */
    @Test
    @DisplayName("only the SYSTEM may confirm — payment decides, not the salon or the customer")
    void onlyTheWebhookConfirms() {
        assertThat(PENDING.canTransitionTo(CONFIRMED, Actor.SALON)).isFalse();
        assertThat(PENDING.canTransitionTo(CONFIRMED, Actor.CUSTOMER)).isFalse();
    }

    /**
     * A no-show is an accusation with a fee attached and no right of reply. It stays with the
     * salon, who is the only party in a position to observe it.
     */
    @Test
    @DisplayName("a customer cannot mark themselves — or be marked — no-show by anyone but the salon")
    void onlyTheSalonMarksNoShow() {
        assertThat(CONFIRMED.canTransitionTo(NO_SHOW, Actor.CUSTOMER)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(NO_SHOW, Actor.SYSTEM)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(NO_SHOW, Actor.SALON)).isTrue();
    }

    /**
     * You cannot cancel an appointment you are sitting in.
     *
     * <p>Once ARRIVED or IN_SERVICE, cancellation is not a scheduling operation — it is a refund
     * conversation, and it should go through one.
     */
    @Test
    @DisplayName("a booking in progress cannot be cancelled by anyone")
    void inProgressCannotBeCancelled() {
        for (Actor actor : Actor.values()) {
            assertThat(ARRIVED.canTransitionTo(CANCELLED, actor))
                    .as("ARRIVED -> CANCELLED by %s", actor).isFalse();
            assertThat(IN_SERVICE.canTransitionTo(CANCELLED, actor))
                    .as("IN_SERVICE -> CANCELLED by %s", actor).isFalse();
        }
    }

    /**
     * Terminal means terminal.
     *
     * <p>Loops over every state and every actor rather than spot-checking, so a transition added
     * out of a terminal state fails here no matter which one it is. Un-cancelling a booking would
     * silently resurrect a slot the salon has already resold.
     */
    @Test
    @DisplayName("nothing leaves COMPLETED, CANCELLED or NO_SHOW")
    void terminalStatesAreTerminal() {
        for (BookingStatus terminal : new BookingStatus[] { COMPLETED, CANCELLED, NO_SHOW }) {
            assertThat(terminal.isTerminal()).isTrue();
            for (BookingStatus target : BookingStatus.values()) {
                for (Actor actor : Actor.values()) {
                    assertThat(terminal.canTransitionTo(target, actor))
                            .as("%s -> %s by %s must be impossible", terminal, target, actor)
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("the live states are not terminal")
    void liveStatesAreNotTerminal() {
        for (BookingStatus live : new BookingStatus[] { PENDING, CONFIRMED, ARRIVED, IN_SERVICE }) {
            assertThat(live.isTerminal()).as("%s", live).isFalse();
        }
    }

    /** Skipping states would let a booking complete without anyone confirming it happened. */
    @Test
    @DisplayName("states cannot be skipped")
    void noSkipping() {
        assertThat(PENDING.canTransitionTo(COMPLETED, Actor.SALON)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(IN_SERVICE, Actor.SALON)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(COMPLETED, Actor.SALON)).isFalse();
        assertThat(ARRIVED.canTransitionTo(COMPLETED, Actor.SALON)).isFalse();
    }

    /** Time doesn't run backwards. */
    @Test
    @DisplayName("no going back to an earlier state")
    void noReversing() {
        assertThat(CONFIRMED.canTransitionTo(PENDING, Actor.SYSTEM)).isFalse();
        assertThat(IN_SERVICE.canTransitionTo(ARRIVED, Actor.SALON)).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // How a refusal surfaces
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The exception message reaches the caller as a 409 body, so it has to name all three parts.
     * "Illegal transition" alone tells nobody which move was refused or on whose behalf.
     */
    @Test
    @DisplayName("assertTransition names the from, the to and the actor")
    void refusalIsSpecific() {
        assertThatThrownBy(() -> COMPLETED.assertTransition(CANCELLED, Actor.CUSTOMER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("CANCELLED")
                .hasMessageContaining("CUSTOMER");
    }

    @Test
    @DisplayName("assertTransition is silent when the move is legal")
    void legalMovesDoNotThrow() {
        CONFIRMED.assertTransition(CANCELLED, Actor.SALON);
        IN_SERVICE.assertTransition(COMPLETED, Actor.SALON);
    }

    /**
     * Every status must have an entry in the transition map.
     *
     * <p>{@code ALLOWED} is a {@code Map.of(...)}, so a new enum constant added without a
     * corresponding entry compiles cleanly and then throws {@code NullPointerException} inside
     * {@code canTransitionTo} the first time anybody touches that state — in production, on a
     * booking. This catches it at build time instead.
     */
    @Test
    @DisplayName("every status is present in the transition table")
    void everyStatusIsMapped() {
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(status.canTransitionTo(CANCELLED, Actor.CUSTOMER))
                    .as("%s has no entry in ALLOWED — canTransitionTo would NPE", status)
                    .isIn(true, false);
        }
    }
}
