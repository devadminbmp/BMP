package com.bmp.salon.services;

import com.bmp.common.money.Money;
import com.bmp.salon.dto.StylistDtos.*;
import com.bmp.salon.entities.Stylist;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.StylistRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import com.bmp.salon.repositories.StylistServiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-24: stylist / stylist_salon / stylist_service — the portable-identity tables. */
@Service
public class StylistCrudService {

    private final StylistRepository stylists;
    private final StylistSalonRepository stylistSalons;
    /** V025 (Session 51) — a suspended stylist may not be added to any salon. */
    private final StylistSuspensionGuard suspensions;
    private final StylistServiceRepository stylistServices;
    /**
     * Session 66 — so a service id from the request body can be checked against the salon the
     * path authorised. Without it, addService had no way to know whose service it was writing.
     */
    private final com.bmp.salon.repositories.SalonServiceRepository salonServices;
    /** Session 51 — telling the stylist they were removed. */
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    private final StylistSelfService lookups;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StylistCrudService.class);

    public StylistCrudService(StylistRepository stylists, StylistSalonRepository stylistSalons,
                               StylistServiceRepository stylistServices,
                               StylistSuspensionGuard suspensions,
                               com.bmp.common.outbox.OutboxPublisher outbox,
                               StylistSelfService lookups,
                               com.bmp.salon.repositories.SalonServiceRepository salonServices) {
        this.salonServices = salonServices;
        this.outbox = outbox;
        this.lookups = lookups;
        this.stylists = stylists;
        this.stylistSalons = stylistSalons;
        this.stylistServices = stylistServices;
        this.suspensions = suspensions;
    }

    @Transactional
    public StylistResponse create(CreateStylistRequest req) {
        Stylist s = new Stylist(req.userId(), req.name(), BigDecimal.ZERO, 0, false);
        s = stylists.save(s);
        return toResponse(s);
    }

    public StylistResponse getById(UUID id) {
        return stylists.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));
    }

    /**
     * Correct a stylist's name or speciality. Session 44.
     *
     * <h2>The scope check is the whole security story here</h2>
     * A {@code Stylist} row is <b>portable</b> — it is not owned by a salon, by design (a stylist
     * who moves shops keeps their identity and their ratings). So {@code @PreAuthorize} proving
     * the caller owns {@code salonId} proves nothing at all about this {@code stylistId}: without
     * the {@code stylist_salon} check below, any owner could rename any stylist on the platform.
     *
     * <p>That is the same shape as the {@code PUT /salons/{id}} hole found in Session 40 —
     * <b>authorise the path, then trust the body</b> — and it is worth naming every time it
     * recurs, because portable entities make it easy to reintroduce. A stylist not linked to this
     * salon is a 404, not a 403: the answer shouldn't confirm the id exists elsewhere.
     *
     * <p>Editing does not rewrite history. {@code booking_service_item.name_snapshot} froze the
     * name at booking time, so past rows still read as they did on the day. Correcting the roster
     * and rewriting the record are different acts; only the first is offered.
     */
    @Transactional
    public StylistResponse update(UUID salonId, UUID stylistId, UpdateStylistRequest req) {
        stylistSalons.findBySalonIdAndStylistId(salonId, stylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "STYLIST_NOT_AT_THIS_SALON"));

        Stylist s = stylists.findById(stylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));

        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "STYLIST_NAME_REQUIRED");
            }
            s.setName(req.name().trim());
        }
        if (req.speciality() != null) {
            // Empty string CLEARS it — "she doesn't specialise in anything particular" is a real
            // correction. Null above meant "don't touch"; the two are deliberately different.
            s.setSpeciality(req.speciality().isBlank() ? null : req.speciality().trim());
        }
        return toResponse(stylists.save(s));
    }

    /**
     * Put an existing stylist on this salon's team.
     *
     * <h2>Session 48 — one salon at a time, and rejoining keeps the history</h2>
     * V021 added a unique index allowing one ACTIVE {@code stylist_salon} row per stylist. Without
     * the checks below this method would still try the insert and fail with a raw constraint
     * violation — a 500 reading {@code uq_stylist_one_active_salon}, which tells an owner nothing.
     *
     * <p>It also used to insert unconditionally, so re-adding somebody who had left created a
     * SECOND row: they appeared twice in the team list and in the booking picker, and their
     * per-salon rating and review count silently reset to zero on their first day back.
     */
    @Transactional
    public StylistSalonResponse link(UUID salonId, LinkStylistRequest req) {
        stylists.findById(req.stylistId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_NOT_FOUND"));

        // V025 — barred from the platform. One of four paths that can make a stylist active at a
        // salon; the guard is shared so path five doesn't forget it.
        suspensions.assertNotSuspended(req.stylistId(), "owner add");

        // Are they working somewhere already? Checked across ALL salons, not just this one.
        for (StylistSalon other : stylistSalons.findByStylistId(req.stylistId())) {
            if (other.isActive() && !other.getSalonId().equals(salonId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "STYLIST_AT_ANOTHER_SALON: this stylist already works at another salon on "
                        + "BMP. They can only be on one team at a time — they'll need to leave "
                        + "there first, from their own profile.");
            }
        }

        StylistSalon link = stylistSalons.findBySalonIdAndStylistId(salonId, req.stylistId())
                .orElse(null);

        if (link == null) {
            link = new StylistSalon(req.stylistId(), salonId, StylistSalon.ACTIVE, null, 0, true,
                    Instant.now(), null);
        } else if (!link.isActive()) {
            // Coming back. Reuse the OLD row so their rating and review count at THIS salon
            // survive — that continuity is the whole point of never deleting these rows.
            link.setStatus(StylistSalon.ACTIVE);
            link.setLeftAt(null);
            link.setIsAvailableToday(true);
        }
        // else: already active here. Idempotent — return the existing row rather than 409ing an
        // owner who tapped twice.

        link = stylistSalons.save(link);
        return toStylistSalonResponse(link);
    }

    public List<StylistSalonResponse> listForSalon(UUID salonId, String status) {
        List<StylistSalon> found = status != null
                ? stylistSalons.findBySalonIdAndStatus(salonId, status)
                : stylistSalons.findBySalonId(salonId);
        return found.stream().map(this::toStylistSalonResponse).toList();
    }

    /** The cheapest, fastest check in the availability algorithm (BMP-13/14) — keep this endpoint fast and correct. */
    @Transactional
    public AvailableTodayResponse setAvailableToday(UUID salonId, UUID stylistId, AvailableTodayRequest req) {
        StylistSalon link = stylistSalons.findBySalonIdAndStylistId(salonId, stylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_SALON_LINK_NOT_FOUND"));
        link.setIsAvailableToday(req.isAvailableToday());
        return new AvailableTodayResponse(stylistId, salonId, link.isAvailableToday());
    }

    /**
     * The owner ends a stylist's employment. NEVER a DELETE — the row becomes {@code alumni} and
     * the per-salon rating freezes here, permanently.
     *
     * <h2>Session 48 — two things this used to get wrong</h2>
     * <ol>
     *   <li><b>It left {@code is_available_today} set.</b> A stylist who had left was still
     *       flagged available at the salon they left, so the salon's availability could keep
     *       offering them and a customer could book somebody who no longer worked there.</li>
     *   <li><b>It overwrote {@code left_at} on a stylist who had already left.</b> Pressing the
     *       button twice rewrote their leaving date to today, quietly falsifying the history this
     *       method exists to protect.</li>
     * </ol>
     *
     * <p>Both are now handled by {@link StylistSalon#leave(Instant)} — the same method the
     * stylist's own resignation path uses, so "left this salon" means exactly one thing whichever
     * side ends it.
     */
    @Transactional
    public StylistSalonResponse markAlumni(UUID salonId, UUID stylistId,
                                            UUID actorUserId, String actorKind) {
        // actorKind is "salon" or "admin". Logged, not branched on: the effect is identical, and
        // WHO ended an employment is the first question when it is disputed.
        StylistSalon link = stylistSalons.findBySalonIdAndStylistId(salonId, stylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STYLIST_SALON_LINK_NOT_FOUND"));

        if (!link.isActive()) {
            // Idempotent rather than a 409: an owner tapping "remove" on somebody already gone
            // wants them gone, not an error. Returning the existing row keeps the ORIGINAL
            // left_at, which is the real one.
            return toStylistSalonResponse(link);
        }

        Stylist stylist = stylists.findById(stylistId).orElseThrow();
        // Freeze the snapshot BEFORE leaving, so it reflects their standing on their last day.
        link.setSalonRating(stylist.getOverallRating());
        link.leave(Instant.now());

        /*
         * Tell them. Session 51.
         *
         * Losing your workplace should not be discovered by opening the app to an empty calendar
         * — somebody may otherwise turn up for a shift they no longer have.
         *
         * Non-fatal: the removal is committed and correct, and failing it because an email could
         * not be queued would leave a stylist on a team the salon has decided to end. Logged at
         * ERROR because from their side a missing email is indistinguishable from being ignored.
         */
        try {
            String email = null;
            String name = stylist.getName();
            if (stylist.getUserId() != null) {
                var user = lookups.userContact(stylist.getUserId());
                if (user != null) {
                    email = user.email();
                    if (name == null || name.isBlank()) name = user.name();
                }
            }
            outbox.publish(new com.bmp.common.events.StylistRemovedFromSalon(
                    stylistId, salonId, lookups.salonName(salonId),
                    actorKind == null ? "salon" : actorKind, email, name));
        } catch (Exception e) {
            log.error("Stylist {} was removed from salon {} but could not be told ({}). The "
                    + "removal itself is saved.", stylistId, salonId, e.toString());
        }

        return toStylistSalonResponse(link);
    }

    /**
     * Replace the whole set of services this stylist performs here. Session 67.
     *
     * <h2>What this replaced, and why the simpler thing is the right thing</h2>
     * Session 66 built three methods — add one, edit its duration and price, remove one — because
     * the model carried a per-stylist duration and price override. Darshan cut that:
     *
     * <blockquote>"timing is standard for service and applicable all stylish ... stylist can
     * choose service which is salon itself"</blockquote>
     *
     * With no per-stylist numbers to maintain, the only fact left is membership: does Ravi do
     * colour, yes or no. That is a checkbox list, and a checkbox list is a SET. So one method
     * takes the resulting set and makes the database match it.
     *
     * <p>This deletes a whole class of bug rather than fixing it. Add/remove had a duplicate
     * check, an ordering question, and a partial-failure state where three of five ticks saved
     * and the screen disagreed with the database. Replace-set has none: it applied or it didn't.
     *
     * <h2>The validation that survives, because it was never about durations</h2>
     * The Session 66 hole is still closed, and it mattered more than the fields that went away:
     * the service ids arrive in the BODY while the path only authorises {@code salonId}, so every
     * id is checked to belong to this salon. Without that an owner could paste another salon's
     * service id onto their stylist. Same shape as the closure-cancel and join-request holes —
     * <b>an authorised path says who is calling, not what their ids refer to.</b>
     *
     * <h2>Why it computes a diff instead of delete-all-then-insert</h2>
     * Wiping and re-inserting would churn primary keys on every save, so any future row that
     * references a stylist_service id — a booking, an audit entry — would be orphaned by an edit
     * that changed nothing. Rows that are staying are left exactly as they are.
     */
    @Transactional
    public List<StylistServiceResponse> replaceServices(UUID salonId, UUID stylistId,
                                                         StylistServiceRequest req) {
        requireActiveHere(salonId, stylistId);

        List<UUID> requested = req.serviceIds() == null ? List.of() : req.serviceIds();
        // Duplicates in the payload are the client's mistake, not an error worth a 400: the SET
        // "colour, colour, cut" and the set "colour, cut" mean the same thing.
        java.util.Set<UUID> wanted = new java.util.LinkedHashSet<>(requested);

        // Every id must belong to THIS salon and be bookable. Checked before anything is written,
        // so a bad id in the list cannot leave the stylist half-updated.
        for (UUID serviceId : wanted) {
            requireBookableServiceOfThisSalon(salonId, serviceId);
        }

        List<com.bmp.salon.entities.StylistService> existing =
                stylistServices.findByStylistIdAndSalonId(stylistId, salonId);
        java.util.Set<UUID> have = existing.stream()
                .map(com.bmp.salon.entities.StylistService::getServiceId)
                .collect(java.util.stream.Collectors.toSet());

        // ── remove what is no longer ticked ──────────────────────────────────────────────────
        // Hard delete, and that is right for THIS table specifically: the row is a statement about
        // the present ("Ravi does colour"), not a record of anything that happened. Bookings
        // snapshot their own price and duration when created, so removing this cannot rewrite a
        // past appointment. There is no history here to preserve.
        int removed = 0;
        for (com.bmp.salon.entities.StylistService row : existing) {
            if (!wanted.contains(row.getServiceId())) {
                stylistServices.delete(row);
                removed++;
            }
        }

        // ── add what is newly ticked, leaving unchanged rows untouched ───────────────────────
        int added = 0;
        for (UUID serviceId : wanted) {
            if (have.contains(serviceId)) continue;
            com.bmp.salon.entities.SalonService svc = salonServices.findById(serviceId).orElseThrow();
            stylistServices.save(new com.bmp.salon.entities.StylistService(
                    stylistId, salonId, serviceId,
                    /*
                     * actual_duration_minutes is NOT NULL and predates this decision, so it still
                     * has to be written. It is set to the SERVICE'S OWN duration — never a
                     * separate per-stylist figure — so the column can only ever agree with the
                     * service it points at. It is no longer read as a distinct concept anywhere;
                     * a later migration can drop it once no deployed build references it.
                     */
                    svc.getDurationMinutes(),
                    // No price override. One price per service, everywhere. (Session 67.)
                    null));
            added++;
        }

        log.info("Stylist {} at salon {}: services set to {} ({} added, {} removed)",
                stylistId, salonId, wanted.size(), added, removed);
        return listServices(salonId, stylistId);
    }



    public List<StylistServiceResponse> listServices(UUID salonId, UUID stylistId) {
        return stylistServices.findByStylistIdAndSalonId(stylistId, salonId).stream()
                .map(this::toStylistServiceResponse).toList();
    }

    /**
     * A stylist's own specialisations, resolved from THEIR token. Session 66.
     *
     * <h2>Scoped to the salon they currently work at, not to every row they own</h2>
     * A stylist who has worked at three salons has rows for all three: V021 ends the employment
     * but keeps the history, which is deliberate — that history is their CV and it is what makes
     * moving salons possible without starting over.
     *
     * <p>Those old rows must not be shown here. "What you're set up for" is a statement about the
     * job they have now; listing a former employer's prices alongside it would read as current,
     * and the prices belong to a salon they no longer work at.
     *
     * <h2>Empty, not an error, when they are on no team</h2>
     * That is the ordinary state of somebody who just registered as a stylist. The UI says
     * "your salon sets this up once you join" — a 404 here would push that normal state into an
     * error branch on a screen the majority of new stylists see first.
     */
    public List<StylistServiceResponse> myServices(UUID userId) {
        Stylist me = lookups.myProfile(userId);
        return lookups.activeLink(me.getId())
                .map(link -> listServices(link.getSalonId(), me.getId()))
                .orElseGet(List::of);
    }

    // ── the checks, factored out so the three writers cannot drift ───────────────────────────
    //
    // One copy each. Session 65 found four separate places in this codebase where one rule was
    // written twice and the looser copy was the one that ran; three write paths sharing a
    // validation rule is exactly the setup for a fifth.

    /** Same 404 as a missing row: an id must not be probeable for existence. */
    private com.bmp.salon.entities.StylistService ownedRow(UUID salonId, UUID stylistId, UUID rowId) {
        com.bmp.salon.entities.StylistService row = stylistServices.findById(rowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        if (!row.getSalonId().equals(salonId) || !row.getStylistId().equals(stylistId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        return row;
    }

    private void requireActiveHere(UUID salonId, UUID stylistId) {
        boolean active = stylistSalons.findBySalonIdAndStylistId(salonId, stylistId)
                .map(com.bmp.salon.entities.StylistSalon::isActive)
                .orElse(false);
        if (!active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "NOT_ON_YOUR_TEAM: this stylist doesn't currently work at your salon, so you "
                    + "can't set what they do here.");
        }
    }

    private void requireBookableServiceOfThisSalon(UUID salonId, UUID serviceId) {
        if (serviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SERVICE_REQUIRED");
        }
        com.bmp.salon.entities.SalonService svc = salonServices.findById(serviceId)
                // 404, not 403: a service id from another salon must not be distinguishable from
                // one that does not exist, or this endpoint becomes a way to test whether a
                // competitor's service id is real.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SERVICE_NOT_FOUND: that service isn't on your salon's menu."));
        if (!svc.getSalonId().equals(salonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "SERVICE_NOT_FOUND: that service isn't on your salon's menu.");
        }
        if (svc.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SERVICE_ARCHIVED: you've retired that service, so nobody can be booked for "
                    + "it. Bring it back first if you want to offer it again.");
        }
    }

    /**
     * A duration nobody can honour is worse than no duration: it is written into the availability
     * algorithm, so a zero-minute service makes a stylist infinitely bookable and an eight-hour
     * one silently empties their day.
     */
    private static void requireSaneDuration(int minutes) {
        if (minutes < 5 || minutes > 480) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "DURATION_OUT_OF_RANGE: give a time between 5 minutes and 8 hours.");
        }
    }

    private StylistResponse toResponse(Stylist s) {
        return new StylistResponse(s.getId(), s.getName(), s.getUserId(), s.getOverallRating(),
                s.getTotalReviews(), s.isTopStylist(), s.getCreatedAt());
    }

    /**
     * Session 16: resolves the stylist's name so callers don't have to fan out one lookup per
     * row. Same service, adjacent table — the cost is a primary-key hit that Hibernate's
     * first-level cache collapses across a single list() call.
     */
    private StylistSalonResponse toStylistSalonResponse(StylistSalon l) {
        // One lookup, two fields off it. Session 26 added the userId; resolving the Stylist into
        // a variable rather than chaining .map() twice keeps it at a single repository hit.
        Stylist stylist = stylists.findById(l.getStylistId()).orElse(null);
        String name = stylist == null ? null : stylist.getName();
        // Null for any stylist who has never signed up — most of them, early on. The stylist
        // dashboard uses this to find "which of these rows is me"; see StylistDtos.
        UUID stylistUserId = stylist == null ? null : stylist.getUserId();
        return new StylistSalonResponse(l.getId(), l.getStylistId(), name, stylistUserId, l.getSalonId(),
                l.getStatus(), l.isAvailableToday(), l.getSalonRating(), l.getSalonReviewCount(),
                l.getJoinedAt(), l.getLeftAt());
    }

    /**
     * Session 66 — resolves the service it points at, so no client has to.
     *
     * <p>Degrades rather than throws when the service row is gone: {@code serviceName} comes back
     * null and the durations/prices fall back to the stylist's own row. A dangling assignment is a
     * thing the owner must be able to SEE in order to remove it, and failing the whole list
     * because one row is broken hides exactly the row they need.
     */
    private StylistServiceResponse toStylistServiceResponse(com.bmp.salon.entities.StylistService s) {
        com.bmp.salon.entities.SalonService svc =
                s.getServiceId() == null ? null : salonServices.findById(s.getServiceId()).orElse(null);

        return new StylistServiceResponse(
                s.getId(), s.getStylistId(), s.getServiceId(),
                svc == null ? null : svc.getName(),
                svc == null ? null : svc.getCategory(),
                /*
                 * Session 67 — the SERVICE'S duration and price, not the stylist's.
                 *
                 * Falls back to the stored column only when the service row has vanished, purely
                 * so a dangling assignment still renders something rather than throwing. In every
                 * normal case the number comes from the service, because that is where it lives:
                 * "timing is standard for service and applicable all stylish".
                 */
                svc == null ? s.getActualDurationMinutes() : svc.getDurationMinutes(),
                svc == null || svc.getPricePaise() == null ? null : svc.getPricePaise().paise(),
                svc != null && svc.isArchived());
    }
}
