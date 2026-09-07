package com.bmp.common.events;

import java.util.UUID;

/**
 * A salon was approved, rejected or suspended by moderation. Session 46.
 *
 * <h2>Why this event had to exist</h2>
 * {@code SalonModerationService.decide} published <b>nothing</b>. A moderator approved a salon and
 * the owner found out by opening the app and guessing; a moderator rejected one and the owner
 * found out never. Meanwhile the owner's dashboard rendered a full working desk in every state,
 * so a rejected owner would build a service menu, invite staff, set opening hours, and wait for
 * bookings that could not arrive.
 *
 * <p>Approval is the single most anticipated moment in a partner's relationship with BMP — it is
 * the thing they signed up for and are actively waiting on. Sending nothing turns a good outcome
 * into a silence indistinguishable from being ignored.
 *
 * <h2>Contact details travel WITH the event</h2>
 * {@code NotificationDispatcher} holds no service clients by design — every event carries the
 * address it needs. So {@code ownerEmail} and {@code ownerPhone} are resolved by bmp-admin at
 * decision time rather than looked up by the dispatcher. Both are nullable, and the dispatcher
 * sends on whichever channels it actually has: an owner who signed up before email was mandatory
 * has a phone and nothing else.
 *
 * @param aggregateId  the salon id. Named {@code aggregateId} to satisfy {@link DomainEvent}'s
 *                     contract, which the outbox uses for ordering per aggregate.
 * @param status       {@code approved} | {@code rejected} | {@code suspended}. Deliberately the
 *                     raw status rather than a boolean: unlike a coupon decision there are three
 *                     genuinely different outcomes here, and suspension of a live salon is not a
 *                     kind of rejection — it stops a business that was already trading.
 * @param decisionNote the moderator's reason. <b>On a rejection this IS the message</b> — a
 *                     refusal with no reason produces a support ticket every single time, and the
 *                     owner has nothing to act on. bmp-admin already requires it (min 10 chars)
 *                     for exactly that reason.
 * @param canResubmit  whether the owner can fix and come back. True only on rejection. Carried on
 *                     the event so the notification's call to action matches what the app will
 *                     actually let them do — a mail saying "resubmit" beside a screen with no
 *                     resubmit button is worse than a mail that says nothing.
 */
public record SalonStatusChanged(
        UUID aggregateId,
        /**
         * The human reference — BMPS001. V017 (Session 48). Nullable: salons created before that
         * migration have none, and an email that says "Reference: null" is worse than one that
         * quietly falls back to the id.
         *
         * <p>Carried on the event rather than looked up by the consumer, for the same reason the
         * owner's contact details are: bmp-notification must not need a synchronous call into
         * bmp-salon to render a message, or an outage there becomes an outage here.
         */
        String salonReference,
        String salonName,
        String status,
        UUID ownerUserId,
        String ownerName,
        String ownerEmail,
        String ownerPhone,
        String decisionNote,
        boolean canResubmit
) implements DomainEvent {

    @Override
    public String eventType() {
        return "salon.status.changed";
    }
}
