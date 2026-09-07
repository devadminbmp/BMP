package com.bmp.common.events;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A salon approved or declined a stylist's leave. Session 49.
 *
 * <h2>Why this one matters more than most notifications</h2>
 * Leave is the request people make plans around. A stylist who asked for four days off in
 * October and hears nothing will either book the flights and not turn up, or cancel the trip
 * unnecessarily — and both outcomes come from the same silence.
 *
 * <p>The in-app screen says "not approved yet — you're still on the calendar", which is the
 * right message while they're looking at it. This event is for when they aren't.
 *
 * <h2>The dates are in the event, not just the decision</h2>
 * "Your leave was approved" is nearly useless on its own — people have more than one request
 * outstanding, and an email that doesn't say WHICH dates forces them back into the app to check,
 * which is the thing the email was supposed to save them. So the range travels with it and the
 * email leads with it.
 *
 * @param aggregateId  the leave request id
 * @param stylistId    who asked
 * @param salonId      who decided
 * @param salonName    named — "your salon" is not a useful thing to be told
 * @param startsOn     first day of leave, INCLUSIVE
 * @param endsOn       last day of leave, INCLUSIVE. Equal to startsOn for a single day
 * @param wholeDay     false for a partial day, where the email must also carry the times
 * @param approved     true = the calendar is now blocked for those dates
 * @param decisionNote the salon's reason. Required on a decline
 * @param stylistEmail where to send it. Null means we hold no address — logged, not guessed
 * @param stylistName  for the greeting
 */
public record StylistLeaveDecided(
        UUID aggregateId,
        UUID stylistId,
        UUID salonId,
        String salonName,
        LocalDate startsOn,
        LocalDate endsOn,
        boolean wholeDay,
        boolean approved,
        String decisionNote,
        String stylistEmail,
        String stylistName
) implements DomainEvent {

    @Override
    public String eventType() {
        return "stylist.leave.decided";
    }
}
