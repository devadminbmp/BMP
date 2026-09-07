package com.bmp.common.events;

import java.util.UUID;

/**
 * A salon ended a stylist's employment. Session 51.
 *
 * <h2>Not the same thing as a suspension</h2>
 * {@link StylistSuspensionChanged} is the platform barring somebody from BMP entirely. This is one
 * salon deciding they no longer work there — they can join another salon tomorrow, and the email
 * says so, because the two are easy to confuse when you are on the receiving end of either.
 *
 * <h2>Why an email at all</h2>
 * Losing your workplace is not something to discover by opening an app and finding an empty
 * calendar. Somebody may otherwise turn up for a shift they no longer have.
 *
 * @param actorKind "salon" when the owner or manager did it, "admin" when BMP did. The email
 *                  differs: a stylist removed by BMP rather than by their salon needs to be told
 *                  that, or they will ring the salon about a decision the salon did not make.
 */
public record StylistRemovedFromSalon(
        UUID aggregateId,
        UUID salonId,
        String salonName,
        String actorKind,
        String stylistEmail,
        String stylistName
) implements DomainEvent {

    @Override
    public String eventType() {
        return "stylist.removed_from_salon";
    }
}
