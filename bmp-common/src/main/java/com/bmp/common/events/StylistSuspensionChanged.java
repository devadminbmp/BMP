package com.bmp.common.events;

import java.util.UUID;

/**
 * A stylist was barred from BMP, or that bar was lifted. V025 (Session 51).
 *
 * <h2>Why one event and not two</h2>
 * They are the same fact at two values, and the email differs only in tone and tense. Two events
 * would mean two handlers that must be kept consistent about a thing that has exactly one source
 * of truth — and the day they drift, somebody gets told they are suspended when they have just
 * been reinstated.
 *
 * <h2>This is the most serious message BMP sends anybody</h2>
 * It ends a person's ability to earn on the platform. So:
 *
 * <ul>
 *   <li>The <b>reason travels with it</b> and is shown in full. A bar with no explanation is one
 *       the stylist cannot contest and support cannot defend.</li>
 *   <li>It says what is NOT affected — their profile, reviews and work history all survive —
 *       because the first fear is that everything is gone.</li>
 *   <li>It says the decision can be reviewed, and how to ask. A message that reads as final when
 *       it is reversible is a message that loses somebody who should not have been lost.</li>
 * </ul>
 *
 * @param aggregateId  the stylist id
 * @param suspended    true = barred; false = the bar has been lifted
 * @param reason       why. Present on both — on a reinstatement it is what they were told
 *                     before, and repeating it is how the email makes sense on its own
 * @param stylistEmail null means we hold no address; the dispatcher logs and gives up rather
 *                     than guessing
 * @param stylistName  for the greeting
 * @param activeSalonCount how many teams they were on when this happened. Shapes the email:
 *                     somebody with a salon needs to be told their calendar is about to empty
 */
public record StylistSuspensionChanged(
        UUID aggregateId,
        boolean suspended,
        String reason,
        String stylistEmail,
        String stylistName,
        int activeSalonCount
) implements DomainEvent {

    @Override
    public String eventType() {
        return "stylist.suspension.changed";
    }
}
