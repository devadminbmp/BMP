package com.bmp.salon.services;

import com.bmp.salon.entities.Stylist;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.StylistRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Platform-level power over a stylist. V025 (Session 51).
 *
 * <h2>Two different removals, and why they must stay different</h2>
 * <ul>
 *   <li><b>Removing from a salon</b> is employment. The salon decides, the link becomes alumni,
 *       and the stylist works elsewhere tomorrow. Admin can do this too — for when a salon can't
 *       or won't — but it is the same act with the same meaning.</li>
 *   <li><b>Suspending</b> is the platform saying this person should not be working through BMP at
 *       all. It survives leaving a salon, blocks joining a new one, and stops them being
 *       bookable anywhere.</li>
 * </ul>
 *
 * <p>Collapsing them would mean either an admin cannot bar anyone, or an owner can bar somebody
 * from every OTHER salon by sacking them. Both are wrong, so they are separate calls with
 * separate consequences.
 *
 * <h2>What suspension does NOT do</h2>
 * It does not delete the stylist, their profile, their reviews or their work history, and it does
 * not rewrite {@code stylist_salon} rows. Salons that employed them in good faith keep an honest
 * record. What changes is that they stop being bookable — enforced in
 * {@link AvailabilityService}, not here — and cannot be added anywhere new.
 */
@Service
public class StylistAdminService {

    private static final Logger log = LoggerFactory.getLogger(StylistAdminService.class);

    private final StylistRepository stylists;
    private final StylistSalonRepository links;
    /** Telling the stylist. This is the most serious message BMP sends anybody. */
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    private final StylistSelfService lookups;

    public StylistAdminService(StylistRepository stylists, StylistSalonRepository links,
                                com.bmp.common.outbox.OutboxPublisher outbox,
                                StylistSelfService lookups) {
        this.stylists = stylists;
        this.links = links;
        this.outbox = outbox;
        this.lookups = lookups;
    }

    /**
     * Bar a stylist from the platform.
     *
     * <h2>Existing employment is left alone, deliberately</h2>
     * The tempting move is to also flip every active link to alumni — "remove them everywhere".
     * That destroys information: a salon would find their stylist gone with no record of the
     * employment having existed, and re-instating the suspension later could not restore it.
     *
     * <p>Instead the link stays and the stylist stops being bookable. The salon sees somebody on
     * their team with no availability, is told why by email, and can remove them properly if they
     * choose to. That is recoverable in both directions.
     *
     * @param reason required. Shown to the stylist — a bar they cannot contest is not a decision,
     *               it is just a wall.
     */
    @Transactional
    public Stylist suspend(UUID stylistId, String reason, UUID byStaffId) {
        Stylist s = stylists.findById(stylistId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));
        if (s.isSuspended()) {
            // Idempotent — the caller's intent ("this person is barred") is already true, and the
            // ORIGINAL timestamp and reason are what a dispute will turn on.
            log.info("Stylist {} is already suspended (since {}) — no change.",
                    stylistId, s.getSuspendedAt());
            return s;
        }
        try {
            s.suspend(reason, byStaffId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        stylists.save(s);

        List<StylistSalon> active = links.findByStylistId(stylistId).stream()
                .filter(StylistSalon::isActive).toList();
        log.warn("Stylist {} SUSPENDED from BMP by staff {} — reason: {}. They are on {} active "
                + "salon team(s), whose links are UNCHANGED; they simply stop being bookable.",
                stylistId, byStaffId, reason, active.size());

        publish(s, true, reason, active.size());
        return s;
    }

    /** Lift the bar. The reason is kept, so the history reads: suspended for X, reinstated on Y. */
    @Transactional
    public Stylist reinstate(UUID stylistId) {
        Stylist s = stylists.findById(stylistId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));
        if (!s.isSuspended()) {
            log.info("Stylist {} is not suspended — nothing to reinstate.", stylistId);
            return s;
        }
        s.reinstate();
        stylists.save(s);
        log.info("Stylist {} REINSTATED. They are bookable again wherever they are still on a "
                + "team; salons that removed them meanwhile must re-add them.", stylistId);

        publish(s, false, s.getSuspensionReason(), activeLinks(stylistId).size());
        return s;
    }

    /**
     * Tell the stylist.
     *
     * <h2>Non-fatal, and loud when it fails</h2>
     * The suspension itself is committed and is what protects customers — failing the whole
     * transaction because an email could not be queued would leave a stylist BOOKABLE that an
     * admin has just decided should not be. That is the wrong way round.
     *
     * <p>But a person barred without being told is the worst outcome this feature has, so a
     * failure here is an ERROR naming exactly that.
     */
    private void publish(Stylist s, boolean suspended, String reason, int activeSalonCount) {
        String email = null;
        String name = s.getName();
        try {
            if (s.getUserId() != null) {
                var user = lookups.userContact(s.getUserId());
                if (user != null) {
                    email = user.email();
                    if (name == null || name.isBlank()) name = user.name();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve contact details for stylist {} ({}). Publishing anyway so "
                    + "the dispatcher can log that somebody was owed this.", s.getId(), e.toString());
        }

        try {
            outbox.publish(new com.bmp.common.events.StylistSuspensionChanged(
                    s.getId(), suspended, reason, email, name, activeSalonCount));
        } catch (Exception e) {
            log.error("Stylist {} was {} but the notification could NOT be queued ({}). THEY HAVE "
                    + "NOT BEEN TOLD — they will find out by opening the app and discovering they "
                    + "cannot be booked, with no reason given.",
                    s.getId(), suspended ? "SUSPENDED" : "reinstated", e.toString());
        }
    }

    /** Every salon this stylist is on right now — what an admin needs before acting. */
    public List<StylistSalon> activeLinks(UUID stylistId) {
        return links.findByStylistId(stylistId).stream().filter(StylistSalon::isActive).toList();
    }

    public Stylist get(UUID stylistId) {
        return stylists.findById(stylistId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));
    }

    /** The console's "who is currently barred" list. */
    public List<Stylist> suspended() {
        return stylists.findAll().stream().filter(Stylist::isSuspended).toList();
    }
}
