package com.bmp.salon.services;

import com.bmp.common.money.Money;
import com.bmp.salon.dto.SalonDtos.*;
import com.bmp.salon.entities.Salon;
import com.bmp.salon.entities.SalonHours;
import com.bmp.salon.entities.SalonPolicy;
import com.bmp.salon.repositories.SalonHoursRepository;
import com.bmp.salon.repositories.SalonPolicyRepository;
import com.bmp.salon.repositories.SalonRepository;
import com.bmp.salon.repositories.SalonServiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BMP-23: salon / salon_policy / salon_hours / salon_service CRUD.
 * NOTE on `location`: the entity stores it as a plain "lat,lng" String (no hibernate-spatial
 * dependency wired in this pass), so proximity search here is an in-memory Haversine
 * calculation over all salons, not a real PostGIS ST_DWithin query. TODO(later ticket):
 * add hibernate-spatial + a native ST_DWithin query once salon count makes this matter.
 */
@Service
public class SalonService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SalonService.class);

    /**
     * salon.status values. A salon is only visible to customers when it is publicly listed.
     *
     * <p><b>Two words mean the same thing here, for a reason.</b> The moderation flow writes
     * {@code approved}, but {@code seed/dev-seed.sql} and any salon created before Session 21
     * use {@code active}. Treating only one as visible would either hide every seeded salon
     * (breaking local dev and demos with no obvious cause) or hide every newly-approved one.
     *
     * <p>Accepting both is the honest fix until the older rows are migrated. TODO: a migration
     * normalising {@code active} → {@code approved}, after which this collapses to one value.
     */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_ACTIVE_LEGACY = "active";

    /** The statuses a customer may see. */
    public static final List<String> PUBLICLY_VISIBLE = List.of(STATUS_APPROVED, STATUS_ACTIVE_LEGACY);

    private final SalonRepository salons;
    private final SalonPolicyRepository policies;
    private final SalonHoursRepository hours;
    private final SalonServiceRepository services;
    private final StaffService staffService;
    private final com.bmp.salon.client.AdminServiceClient admin;
    // V011 (Session 40) — discovery.
    private final com.bmp.salon.repositories.SalonCategoryRepository categories;
    private final com.bmp.salon.repositories.StylistSalonRepository stylistLinks;
    private final com.bmp.salon.repositories.StylistRepository stylists;

    public SalonService(SalonRepository salons, SalonPolicyRepository policies,
                         SalonHoursRepository hours, SalonServiceRepository services,
                         StaffService staffService,
                         com.bmp.salon.client.AdminServiceClient admin,
                         com.bmp.salon.repositories.SalonCategoryRepository categories,
                         com.bmp.salon.repositories.StylistSalonRepository stylistLinks,
                         com.bmp.salon.repositories.StylistRepository stylists) {
        this.salons = salons;
        this.policies = policies;
        this.hours = hours;
        this.services = services;
        this.staffService = staffService;
        this.admin = admin;
        this.categories = categories;
        this.stylistLinks = stylistLinks;
        this.stylists = stylists;
    }

    /**
     * Session 6: {@code ownerUserId} comes from the caller's JWT ({@code AuthenticatedUser}),
     * never from the request body — the creating user always becomes this salon's OWNER via
     * a new salon_staff row, no invite needed for your own salon.
     */
    @Transactional
    public SalonResponse create(CreateSalonRequest req, UUID ownerUserId) {
        String location = req.location().lat() + "," + req.location().lng();
        String strategy = req.stylistAssignmentStrategy() == null ? "least_loaded" : req.stylistAssignmentStrategy();
        Salon s = new Salon(req.name(), location, STATUS_PENDING, strategy);

        /*
         * V011 (Session 40) — the signup form's fields finally land somewhere.
         *
         * `address` has been collected by SalonSignupSheet since Session 15 and thrown away,
         * because CreateSalonRequest had nowhere to put it (PENDING_WORK F2). The owner typed
         * into a field that went nowhere, and nothing said so.
         */
        s.setArea(req.area());
        s.setAddress(req.address());
        s.setAbout(req.about());
        s.setImageUrl(req.imageUrl());
        // Where booking alerts go. Until Session 40 the salon was never told a customer had
        // booked — see the field's javadoc.
        s.setBookingNotifyEmail(req.bookingNotifyEmail());
        s.setBookingNotifyPhone(req.bookingNotifyPhone());

        s = salons.save(s);
        replaceCategories(s.getId(), req.categories());
        staffService.addOwner(s.getId(), ownerUserId);

        // Session 21: put it in the console's moderation queue. Without this the queue is
        // permanently empty and nothing ever gets approved — the salon stays 'pending' and
        // invisible forever, which looks to the owner exactly like being ignored.
        //
        // Best-effort on purpose: bmp-admin being down must not stop a salon owner completing
        // signup. The endpoint is idempotent, so the backfill below is safe to re-run.
        try {
            admin.enqueueSalonReview(s.getId());
        } catch (Exception e) {
            log.error("Salon {} created but could not be queued for review ({}). It will stay "
                    + "pending and invisible until someone re-queues it — see "
                    + "POST /api/v1/admin/salons/{}/enqueue-review.", s.getId(), e.toString(), s.getId());
        }

        return toResponse(s);
    }

    public SalonResponse getById(UUID id) {
        return salons.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
    }

    @Transactional
    public SalonResponse update(UUID id, UpdateSalonRequest req) {
        Salon s = salons.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        /*
         * SESSION 40 — status is NOT settable here.
         *
         * This method used to write it from the request body, on an endpoint that had no
         * @PreAuthorize whatsoever. Any logged-in user could POST their own salon to `approved`
         * and become publicly bookable without ever passing moderation.
         *
         * The controller now requires the salon's own OWNER, which fixes the "anyone" half. This
         * fixes the rest: an owner is still not the person who decides whether their salon is
         * approved. Rejected loudly rather than ignored — a silently dropped field is how
         * somebody concludes the endpoint is broken and starts looking for another way in.
         *
         * The real path is bmp-admin -> InternalSalonController.changeStatus, which records who
         * decided and why.
         */
        if (req.status() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "STATUS_NOT_SELF_SERVICE: a salon's approval status is set by BMP's "
                    + "moderation team, not through this endpoint.");
        }

        if (req.name() != null) s.setName(req.name());
        if (req.location() != null) s.setLocation(req.location().lat() + "," + req.location().lng());
        if (req.stylistAssignmentStrategy() != null) s.setStylistAssignmentStrategy(req.stylistAssignmentStrategy());
        // V011: null means "leave unchanged", the same rule as everywhere else in this service.
        // An owner editing their name through an older client must not blank their address.
        if (req.area() != null) s.setArea(req.area());
        if (req.address() != null) s.setAddress(req.address());
        if (req.about() != null) s.setAbout(req.about());
        if (req.imageUrl() != null) s.setImageUrl(req.imageUrl());
        if (req.bookingNotifyEmail() != null) s.setBookingNotifyEmail(req.bookingNotifyEmail());
        if (req.bookingNotifyPhone() != null) s.setBookingNotifyPhone(req.bookingNotifyPhone());
        if (req.categories() != null) replaceCategories(id, req.categories());
        s.touch();
        return toResponse(s);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // V011 (Session 40) — discovery. Everything below exists because a sweep found that four
    // frontend schemas asked for fields this service had never sent, so browse → salon detail →
    // pick stylist → pick slot had never worked against a real backend.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Everything the salon page needs, in one call.
     *
     * <p>One round trip rather than three, because this is the conversion funnel: a customer who
     * reaches this screen is deciding whether to book, and three sequential requests on a
     * Bengaluru 4G connection is three spinners and two extra chances to fail.
     *
     * <p>Only PUBLICLY VISIBLE salons. A pending or suspended salon returns 404 rather than a
     * page a customer could try to book from — an approved-looking page for an unapproved salon
     * is worse than no page.
     */
    public SalonDetailResponse detail(UUID salonId) {
        Salon s = salons.findById(salonId)
                .filter(x -> PUBLICLY_VISIBLE.contains(x.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        List<com.bmp.salon.entities.SalonService> menu = services.findBySalonId(salonId);

        return new SalonDetailResponse(
                s.getId(), s.getName(), parseLocation(s.getLocation()), s.getStatus(),
                s.getArea(), s.getAddress(), s.getAbout(), s.getImageUrl(),
                s.getRating(), s.getReviewCount(),
                categoriesOf(salonId),
                startingPrice(menu),
                openHoursLabel(salonId),
                menu.stream().map(this::toServiceResponse).toList(),
                publicStylists(salonId));
    }

    /**
     * The stylists a customer may choose from — ACTIVE links only.
     *
     * <p>Alumni are deliberately excluded. {@code stylist_salon} rows are never deleted (their
     * ratings are frozen when someone leaves), so without the status filter a customer would be
     * offered a stylist who no longer works there — and the booking would then fail the
     * availability check with no explanation they could act on.
     *
     * <p>Two queries, not N+1: the links, then every stylist in one {@code findAllById}.
     */
    private List<PublicStylistResponse> publicStylists(UUID salonId) {
        List<com.bmp.salon.entities.StylistSalon> links =
                stylistLinks.findBySalonIdAndStatus(salonId, "active");
        if (links.isEmpty()) {
            return List.of();
        }
        Map<UUID, com.bmp.salon.entities.Stylist> people = stylists
                .findAllById(links.stream().map(com.bmp.salon.entities.StylistSalon::getStylistId).toList())
                .stream().collect(Collectors.toMap(com.bmp.salon.entities.Stylist::getId, x -> x));

        return links.stream()
                .map(link -> {
                    var person = people.get(link.getStylistId());
                    if (person == null) return null; // orphaned link; skip rather than NPE
                    return new PublicStylistResponse(
                            link.getStylistId(), person.getName(), person.getSpeciality(),
                            // THIS salon's rating, not the lifetime cross-salon one. A colourist
                            // who was excellent elsewhere is not evidence about this shop.
                            link.getSalonRating(),
                            // Primitive int on the entity (NOT NULL column) — no null guard needed.
                            link.getSalonReviewCount(),
                            link.isAvailableToday());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Lower-cased, de-duplicated, order preserved. */
    private void replaceCategories(UUID salonId, List<String> requested) {
        if (requested == null) return;
        categories.deleteBySalonId(salonId);
        java.util.LinkedHashSet<String> clean = requested.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        clean.forEach(c -> categories.save(new com.bmp.salon.entities.SalonCategory(salonId, c)));
    }

    private List<String> categoriesOf(UUID salonId) {
        return categories.findBySalonId(salonId).stream()
                .map(com.bmp.salon.entities.SalonCategory::getCategory)
                .sorted()
                .toList();
    }

    /**
     * The cheapest service on the menu — "from ₹350".
     *
     * <p>Derived, never stored. A stored copy goes stale the moment an owner edits a price, and
     * a wrong "from" price is the kind of thing a customer notices at the counter.
     *
     * <p>Null for an empty menu, which is a real state for a salon mid-setup. The UI omits the
     * line rather than showing "from ₹0".
     */
    private Long startingPrice(List<com.bmp.salon.entities.SalonService> menu) {
        return menu.stream()
                .mapToLong(x -> x.getPricePaise().paise())
                .min()
                .stream().boxed().findFirst().orElse(null);
    }

    /**
     * {@code salon_hours} as one line a person can read: "Mon–Sat, 10:00–20:00".
     *
     * <p>Composed here rather than stored. The per-weekday rows stay authoritative for the
     * availability algorithm; this is prose, and prose about structured data should be derived
     * from it or the two eventually disagree.
     *
     * <p>Falls back to listing days when the hours aren't uniform, and returns null when none are
     * set — the UI says "Hours not set yet" rather than inventing "9 to 5", which is exactly the
     * kind of invented content Session 20 stripped out of About/Contact.
     */
    private String openHoursLabel(UUID salonId) {
        List<com.bmp.salon.entities.SalonHours> rows = hours.findBySalonId(salonId);
        if (rows.isEmpty()) return null;

        boolean uniform = rows.stream()
                .allMatch(r -> r.getOpenTime().equals(rows.get(0).getOpenTime())
                            && r.getCloseTime().equals(rows.get(0).getCloseTime()));

        String[] shortDays = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
        List<Integer> days = rows.stream()
                .map(com.bmp.salon.entities.SalonHours::getDayOfWeek).sorted().toList();
        String window = rows.get(0).getOpenTime() + "–" + rows.get(0).getCloseTime();

        if (uniform && days.size() == 7) {
            return "Every day, " + window;
        }
        if (uniform) {
            // Contiguous run gets an en-dash; anything else is listed. "Mon, Tue, Thu" is honest
            // where "Mon–Thu" would silently claim Wednesday.
            boolean contiguous = days.get(days.size() - 1) - days.get(0) == days.size() - 1;
            String label = contiguous && days.size() > 2
                    ? shortDays[days.get(0)] + "–" + shortDays[days.get(days.size() - 1)]
                    : days.stream().map(d -> shortDays[d]).collect(Collectors.joining(", "));
            return label + ", " + window;
        }
        return days.stream().map(d -> shortDays[d]).collect(Collectors.joining(", "))
               + " — hours vary by day";
    }

    /**
     * Customer-facing proximity search.
     *
     * <p><b>Session 21 FIX — this used to return every salon regardless of status.</b> Salons
     * are created {@code pending} and are supposed to be invisible until a human has confirmed
     * they're a real business at a real address. Without this filter that whole moderation gate
     * was decorative: anyone who completed the signup wizard was instantly live to customers,
     * under BMP's brand.
     *
     * <p>Only {@code approved} salons are returned. A suspended salon disappears from search
     * the moment it's suspended, which is the entire point of having a suspend action.
     */
    /**
     * The discovery list. V011 (Session 40) grew this from three fields to something a customer
     * can actually choose from.
     *
     * <h2>The N+1 this deliberately avoids</h2>
     * Every row needs its categories and its cheapest service. Fetching those per salon would be
     * two extra queries per row on the platform's most-viewed screen. Instead: the salons, then
     * ALL their categories in one query, then ALL their services in one query, grouped in memory.
     * Three queries regardless of how many salons come back.
     *
     * @param category optional filter — "salons that do hair colour". This is the query the
     *                 category child table exists for; a comma-separated column could not answer
     *                 it.
     */
    public List<NearbySalonResponse> near(double lat, double lng, double radiusKm, String category) {
        List<Salon> visible = salons.findByStatusIn(PUBLICLY_VISIBLE);

        if (category != null && !category.isBlank()) {
            java.util.Set<UUID> matching =
                    new java.util.HashSet<>(categories.findSalonIdsByCategory(category.trim()));
            visible = visible.stream().filter(s -> matching.contains(s.getId())).toList();
        }
        if (visible.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = visible.stream().map(Salon::getId).toList();

        Map<UUID, List<String>> catsBySalon = categories.findBySalonIdIn(ids).stream()
                .collect(Collectors.groupingBy(
                        com.bmp.salon.entities.SalonCategory::getSalonId,
                        Collectors.mapping(com.bmp.salon.entities.SalonCategory::getCategory,
                                Collectors.toList())));

        Map<UUID, Long> cheapestBySalon = services.findBySalonIdIn(ids).stream()
                .collect(Collectors.toMap(
                        com.bmp.salon.entities.SalonService::getSalonId,
                        x -> x.getPricePaise().paise(),
                        Math::min));

        return visible.stream()
                .map(s -> {
                    double dist = distanceFrom(lat, lng, s);
                    return new NearbySalonResponse(
                            s.getId(), s.getName(), dist,
                            s.getArea(), s.getImageUrl(),
                            // Null rating = "no reviews yet", NOT zero. The client renders "New".
                            s.getRating(), s.getReviewCount(),
                            catsBySalon.getOrDefault(s.getId(), List.of()),
                            cheapestBySalon.get(s.getId()));
                })
                .filter(r -> r.distanceKm() <= radiusKm)
                .sorted((a, b) -> Double.compare(a.distanceKm(), b.distanceKm()))
                .toList();
    }

    /**
     * Distance, tolerating a salon with no or malformed coordinates.
     *
     * <p>Session 40. This used to be inline and would throw on a null or unparseable
     * {@code location} — and F3 in PENDING_WORK says every salon created through signup gets
     * PLACEHOLDER coordinates because nothing geocodes the address. One bad row would have
     * taken down the whole discovery list with a NumberFormatException.
     *
     * <p>Returns {@code MAX_VALUE} so such a salon sorts last and is filtered out by the radius,
     * rather than crashing the request for everyone else.
     */
    private double distanceFrom(double lat, double lng, Salon s) {
        try {
            String[] parts = s.getLocation().split(",");
            return haversineKm(lat, lng, Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (Exception e) {
            log.warn("Salon {} has an unusable location ({}), so it can't be placed on the map "
                    + "and won't appear in nearby results.", s.getId(), s.getLocation());
            return Double.MAX_VALUE;
        }
    }

    /** "12.97,77.59" → LatLng, or null when the salon has no usable coordinates. */
    private LatLng parseLocation(String raw) {
        try {
            String[] parts = raw.split(",");
            return new LatLng(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public PolicyResponse upsertPolicy(UUID salonId, PolicyRequest req) {
        salons.findById(salonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        SalonPolicy p = policies.findBySalonId(salonId).orElse(null);
        if (p == null) {
            p = new SalonPolicy(salonId, req.template(), req.freeCancelHours(), req.lateGraceMinutes(),
                    req.requirePrepayment(), req.slotGranularityMinutes());
            p = policies.save(p);
        } else {
            p.setTemplate(req.template());
            p.setFreeCancelHours(req.freeCancelHours());
            p.setLateGraceMinutes(req.lateGraceMinutes());
            p.setRequirePrepayment(req.requirePrepayment());
            p.setSlotGranularityMinutes(req.slotGranularityMinutes());
            p.touch();
        }

        /*
         * Commission is changed ONLY when explicitly supplied, and null means "leave it alone".
         *
         * This endpoint is reachable by the salon's own owner, who is editing their cancellation
         * window. Reading commission unconditionally from the request body would mean an owner
         * could set their own rate to zero by adding one field to a PUT — and, more likely,
         * that an owner saving an unrelated change through a client that omits the field would
         * silently reset their negotiated rate to the default. Both are bad; the second is the
         * one that actually happens.
         *
         * Bounds mirror V009's CHECK constraint so the caller gets a 400 naming the problem
         * rather than a 500 from the database. 5000 bps catches the predictable mistake of
         * typing "12" (meaning 12%) into a basis-points field — which would be 0.12%.
         */
        if (req.commissionBps() != null) {
            int bps = req.commissionBps();
            if (bps < 0 || bps > 5000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "INVALID_COMMISSION_BPS: expected basis points between 0 and 5000 (1200 = 12%).");
            }
            p.setCommissionBps(bps);
            p.touch();
        }

        applyV010Policy(p, req);
        return toPolicyResponse(p);
    }

    public PolicyResponse getPolicy(UUID salonId) {
        return policies.findBySalonId(salonId).map(this::toPolicyResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND"));
    }

    /** Bulk upsert of all 7 days at once — idempotent (re-running with the same body doesn't duplicate rows). */
    @Transactional
    public HoursResponse upsertHours(UUID salonId, HoursRequest req) {
        salons.findById(salonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        for (HourEntry entry : req.hours()) {
            if (entry.dayOfWeek() < 0 || entry.dayOfWeek() > 6) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DAY_OF_WEEK");
            }
            if (entry.closeTime().compareTo(entry.openTime()) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CLOSE_TIME_MUST_BE_AFTER_OPEN_TIME");
            }
            SalonHours existing = hours.findBySalonIdAndDayOfWeek(salonId, entry.dayOfWeek()).orElse(null);
            if (existing == null) {
                hours.save(new SalonHours(salonId, entry.dayOfWeek(), entry.openTime(), entry.closeTime()));
            } else {
                existing.setOpenTime(entry.openTime());
                existing.setCloseTime(entry.closeTime());
            }
        }
        List<HourEntry> echoed = hours.findBySalonId(salonId).stream()
                .map(h -> new HourEntry(h.getDayOfWeek(), h.getOpenTime(), h.getCloseTime()))
                .toList();
        return new HoursResponse(salonId, echoed);
    }

    @Transactional
    public ServiceResponse addService(UUID salonId, ServiceRequest req) {
        salons.findById(salonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        com.bmp.salon.entities.SalonService svc = new com.bmp.salon.entities.SalonService(
                salonId, req.name(), Money.ofPaise(req.pricePaise()), req.durationMinutes(), req.requiresStylistAssignment());
        // V011 — set after construction: the constructor predates the column and adding a
        // parameter would break every other caller for one optional field.
        svc.setCategory(req.category());
        svc = services.save(svc);
        return toServiceResponse(svc);
    }

    public List<ServiceResponse> listServices(UUID salonId) {
        return services.findBySalonId(salonId).stream().map(this::toServiceResponse).toList();
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }

    private SalonResponse toResponse(Salon s) {
        // Session 40: was `new LatLng(Double.parseDouble(...))` inline and threw on a salon with
        // a null or malformed location. Every salon created through signup gets PLACEHOLDER
        // coordinates today (PENDING_WORK F3, nothing geocodes the address), so one bad row could
        // 500 this endpoint — which bmp-booking calls on every single booking.
        return new SalonResponse(s.getId(), s.getName(), parseLocation(s.getLocation()), s.getStatus(),
                s.getStylistAssignmentStrategy(), s.getCreatedAt(), s.getUpdatedAt(),
                s.getBookingNotifyEmail(), s.getBookingNotifyPhone());
    }

    /**
     * The V010 cancellation-fee and reschedule settings. Session 37.
     *
     * <h2>Null means "leave unchanged", for every field</h2>
     * Same rule as commission, and for the same reason it was added there: an owner saving an
     * unrelated change through a client that doesn't know about these fields would otherwise
     * reset their entire fee policy to zero. That is a silent revenue bug nobody notices until
     * the month's numbers come in.
     *
     * <h2>Validation mirrors the CHECK constraints in V010</h2>
     * Deliberate duplication. The database is the backstop no code path can route around; this
     * layer exists so an owner gets a 400 that names the problem in words, rather than a 500
     * from a constraint violation. If the two ever disagree, the database wins and the owner
     * gets the worse message — so they are kept side by side.
     */
    private void applyV010Policy(SalonPolicy p, PolicyRequest req) {
        boolean changed = false;

        if (req.lateCancelHours() != null) {
            // Must nest inside the free-cancel window, or the middle band has negative width and
            // the tier lookup silently picks the wrong rate.
            if (req.lateCancelHours() < 0 || req.lateCancelHours() > p.getFreeCancelHours()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "INVALID_LATE_CANCEL_HOURS: must be between 0 and freeCancelHours ("
                        + p.getFreeCancelHours() + ").");
            }
            p.setLateCancelHours(req.lateCancelHours());
            changed = true;
        }
        if (req.lateCancelFeeBps() != null) {
            p.setLateCancelFeeBps(requireFeeBps(req.lateCancelFeeBps(), "lateCancelFeeBps"));
            changed = true;
        }
        if (req.noNoticeFeeBps() != null) {
            p.setNoNoticeFeeBps(requireFeeBps(req.noNoticeFeeBps(), "noNoticeFeeBps"));
            changed = true;
        }
        if (req.rescheduleNoticeHours() != null) {
            if (req.rescheduleNoticeHours() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "INVALID_RESCHEDULE_NOTICE_HOURS: cannot be negative.");
            }
            p.setRescheduleNoticeHours(req.rescheduleNoticeHours());
            changed = true;
        }
        if (req.maxReschedulesPerBooking() != null) {
            if (req.maxReschedulesPerBooking() < 0 || req.maxReschedulesPerBooking() > 10) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "INVALID_MAX_RESCHEDULES: expected 0 to 10. 0 disables customer rescheduling.");
            }
            p.setMaxReschedulesPerBooking(req.maxReschedulesPerBooking());
            changed = true;
        }
        if (req.salonCanRescheduleDirectly() != null) {
            p.setSalonCanRescheduleDirectly(req.salonCanRescheduleDirectly());
            changed = true;
        }
        if (req.rescheduleKeepsOriginalClock() != null) {
            // Logged at WARN when a salon opens the loophole. Not blocked — it is their policy
            // and their money — but it should never happen by accident, and if a salon later
            // asks why their fee revenue is zero, this line is the answer.
            if (!req.rescheduleKeepsOriginalClock()) {
                log.warn("Salon {} has set rescheduleKeepsOriginalClock=false. Cancellation fees "
                        + "will now be measured against the RESCHEDULED time, so a customer can "
                        + "reschedule out of the fee window and then cancel free.", p.getSalonId());
            }
            p.setRescheduleKeepsOriginalClock(req.rescheduleKeepsOriginalClock());
            changed = true;
        }

        if (changed) {
            p.touch();
        }
    }

    private int requireFeeBps(int bps, String field) {
        if (bps < 0 || bps > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "INVALID_" + field.toUpperCase(java.util.Locale.ROOT)
                    + ": expected basis points between 0 and 10000 (5000 = 50%).");
        }
        return bps;
    }

    private PolicyResponse toPolicyResponse(SalonPolicy p) {
        return new PolicyResponse(p.getId(), p.getSalonId(), p.getTemplate(), p.getFreeCancelHours(),
                p.getLateGraceMinutes(), p.isRequirePrepayment(), p.getSlotGranularityMinutes(),
                p.getCommissionBps(),
                p.getLateCancelHours(), p.getLateCancelFeeBps(), p.getNoNoticeFeeBps(),
                p.getRescheduleNoticeHours(), p.getMaxReschedulesPerBooking(),
                p.isSalonCanRescheduleDirectly(), p.isRescheduleKeepsOriginalClock());
    }

    private ServiceResponse toServiceResponse(com.bmp.salon.entities.SalonService s) {
        return new ServiceResponse(s.getId(), s.getSalonId(), s.getName(), s.getPricePaise().paise(),
                s.getDurationMinutes(), s.isRequiresStylistAssignment(),
                s.getCategory()); // V011
    }
}
