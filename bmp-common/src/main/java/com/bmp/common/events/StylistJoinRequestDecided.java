package com.bmp.common.events;

import java.util.UUID;

/**
 * A salon answered a stylist's request to join its team. Session 49.
 *
 * <h2>The silence this ends</h2>
 * Session 48 built the whole self-signup flow — a stylist registers, searches for the salon they
 * work at, and asks to be added — and then told them nothing. The decision landed in a list they
 * had to remember to open. A stylist who has asked and heard nothing has no way to tell the
 * difference between "not looked at yet", "declined", and "the app is broken", and the rational
 * response to all three is to ask again.
 *
 * <p>The owner's side had the same hole: a request arrived in the dashboard and nobody was told.
 * A request nobody sees is a person waiting forever for a decision that never gets made — which
 * is why {@link StylistJoinRequestRaised} exists too.
 *
 * <h2>The decision note travels with the event</h2>
 * A decline REQUIRES a note server-side, and that note is the entire value of the email. Sending
 * "your request was declined" without it produces the same request again next week; sending the
 * owner's actual words ("we don't have anyone by that name here") lets the stylist correct a
 * genuine mistake — they searched for the wrong branch, or gave a name the owner didn't
 * recognise.
 *
 * <p>Carried inline rather than as an id the dispatcher would fetch: bmp-notification must never
 * need a synchronous call back into bmp-salon to render an email.
 *
 * @param aggregateId    the join request id
 * @param stylistId      who asked
 * @param salonId        who answered
 * @param salonName      named, because "a salon" is not a useful thing to be told about
 * @param accepted       true = they're on the team
 * @param decisionNote   the owner's reason. Required on a decline; usually null on an accept
 * @param stylistEmail   where to send it. Null means we hold no address — logged, not guessed
 * @param stylistName    for the greeting
 */
public record StylistJoinRequestDecided(
        UUID aggregateId,
        UUID stylistId,
        UUID salonId,
        String salonName,
        boolean accepted,
        String decisionNote,
        String stylistEmail,
        String stylistName
) implements DomainEvent {

    @Override
    public String eventType() {
        return "stylist.join_request.decided";
    }
}
