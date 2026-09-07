package com.bmp.salon.services;

import com.bmp.salon.entities.Stylist;
import com.bmp.salon.repositories.StylistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * One place that answers "may this stylist work?". V025 (Session 51).
 *
 * <h2>Why this is a shared component and not a check in each caller</h2>
 * There are <b>four</b> independent ways a stylist can end up active at a salon:
 *
 * <ol>
 *   <li>{@code StaffService} — they redeem an invite token</li>
 *   <li>{@code StylistCrudService.link} — an owner adds them directly</li>
 *   <li>{@code StylistSelfService.decide} — an owner accepts their join request</li>
 *   <li>and any fifth one somebody adds next month</li>
 * </ol>
 *
 * A suspension that is enforced in three of those is not a suspension. Copying the check into
 * each caller means the next path added is the one that forgets it — and the failure is silent,
 * because everything looks normal right up until a barred stylist is taking bookings again.
 *
 * <p>So the rule lives here, is called by name, and is easy to find when writing path five.
 *
 * <h2>What this deliberately does NOT do</h2>
 * It does not touch existing links. Suspending somebody does not silently rewrite their
 * employment history at salons that hired them in good faith — {@code stylist_salon} rows stay as
 * they are. What changes is that they stop being bookable, which
 * {@code AvailabilityService} enforces separately by producing no slots.
 */
@Component
public class StylistSuspensionGuard {

    private static final Logger log = LoggerFactory.getLogger(StylistSuspensionGuard.class);

    private final StylistRepository stylists;

    public StylistSuspensionGuard(StylistRepository stylists) {
        this.stylists = stylists;
    }

    /**
     * Refuse if this stylist is barred from the platform.
     *
     * @param context what was being attempted, for the log — "invite", "owner add", "join
     *                request". A suspended stylist repeatedly appearing in these logs is somebody
     *                still trying to get back in, which is worth knowing.
     * @throws ResponseStatusException 409, with a message the salon can act on
     */
    public void assertNotSuspended(UUID stylistId, String context) {
        Stylist s = stylists.findById(stylistId).orElse(null);
        // A missing stylist is somebody else's error to report — this guard only answers the one
        // question it is named for, and swallowing "not found" here would hide it from the caller
        // that actually knows what to do about it.
        if (s == null || !s.isSuspended()) return;

        log.warn("Blocked an attempt to add SUSPENDED stylist {} to a salon via {}. Suspended {} "
                + "for: {}", stylistId, context, s.getSuspendedAt(), s.getSuspensionReason());

        /*
         * The salon is told the stylist is unavailable and to contact support — NOT the reason.
         *
         * The reason may reference a safety complaint or another salon's grievance, and handing
         * that to whoever happens to try adding them would be a disclosure about the stylist to a
         * third party. The stylist themselves gets the full reason, in their own email and on
         * their own profile.
         */
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "STYLIST_SUSPENDED: this stylist can't be added to a salon at the moment. "
                + "If you think that's wrong, contact BMP support and quote their name.");
    }

    /** Non-throwing, for read paths like availability that must return empty rather than error. */
    public boolean isSuspended(UUID stylistId) {
        return stylists.findById(stylistId).map(Stylist::isSuspended).orElse(false);
    }
}
