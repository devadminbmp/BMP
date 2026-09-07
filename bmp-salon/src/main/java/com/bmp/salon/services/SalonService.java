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
    /** Passed review. NOT visible — the owner still has to press Go Live. See V018. */
    public static final String STATUS_APPROVED = "approved";
    /** Live: the owner has published. The only status a customer can find. */
    public static final String STATUS_ACTIVE = "active";
    /** @deprecated kept so older call sites still compile; identical to {@link #STATUS_ACTIVE}. */
    @Deprecated
    public static final String STATUS_ACTIVE_LEGACY = STATUS_ACTIVE;

    /**
     * The statuses a customer may see. V018 (Session 48) — 'approved' was REMOVED from this list.
     *
     * <h2>Why approval is no longer enough</h2>
     * A moderator's approval used to publish the salon immediately, usually before the owner had
     * added a single service, stylist or opening hour. Customers found a listing they could not
     * book, which is worse than not finding it: it spends the salon's one first impression on a
     * dead end, and the owner never even knew they were live.
     *
     * <p>Approval now means "you passed review"; the owner presses Go Live when they are ready.
     * Same decision a real salon makes about its own shutter.
     *
     * <p>V018 promoted every existing 'approved' salon to 'active' precisely so this narrowing
     * could not silently unpublish a business that was already trading.
     */
    public static final List<String> PUBLICLY_VISIBLE = List.of(STATUS_ACTIVE);

    /**
     * V014 — how many photos one salon may show.
     *
     * <p>The gallery renders in full on a page customers open on mobile data. Unbounded, a salon
     * can make its own page unusable without ever seeing the problem on office wifi. Twelve is
     * enough to show a room, a few results and the team; the limit is stated in the error so it
     * reads as a rule rather than a malfunction.
     */
    public static final int MAX_SALON_PHOTOS = 12;

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
    /** V014 (Session 44) — the salon gallery. */
    private final com.bmp.salon.repositories.SalonPhotoRepository photos;

    /**
     * Object storage, for deleting the files behind uploaded images. V015 (Session 44).
     *
     * <p>Nullable-by-config in principle — a deployment without the S3 SDK has no such bean — but
     * bmp-salon declares the dependency, so it is always present here. Made explicit rather than
     * Optional because a salon service that cannot clean up its own uploads is misconfigured, and
     * failing at startup with a missing-bean error is the right way to find that out.
     */
    private final com.bmp.common.storage.ObjectStorage storage;

    /** Resolves the owner's name/email/phone for the submission receipt. Session 48. */
    private final com.bmp.salon.client.UserServiceClient users;

    /**
     * Transactional outbox. Session 48.
     *
     * <p>The event is written in the SAME transaction as the salon row, so the receipt email and
     * the salon it confirms cannot disagree: either both exist or neither does. Publishing to
     * Kafka directly from here would let a broker hiccup send "we've got your application" for a
     * salon whose insert then rolled back.
     */
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    /**
     * Session 64 — so the customer's salon page can say "Closed for Diwali" instead of quietly
     * showing an empty slot list. Closures have blocked booking since V019; nothing told the person.
     */
    private final com.bmp.salon.repositories.SalonClosureRepository closures;

    public SalonService(SalonRepository salons, SalonPolicyRepository policies,
                         SalonHoursRepository hours, SalonServiceRepository services,
                         StaffService staffService,
                         com.bmp.salon.client.AdminServiceClient admin,
                         com.bmp.salon.repositories.SalonCategoryRepository categories,
                         com.bmp.salon.repositories.StylistSalonRepository stylistLinks,
                         com.bmp.salon.repositories.StylistRepository stylists,
                         com.bmp.salon.repositories.SalonPhotoRepository photos,
                         com.bmp.common.storage.ObjectStorage storage,
                         com.bmp.salon.client.UserServiceClient users,
                         com.bmp.common.outbox.OutboxPublisher outbox,
                         com.bmp.salon.repositories.SalonClosureRepository closures) {
        this.salons = salons;
        this.policies = policies;
        this.hours = hours;
        this.services = services;
        this.staffService = staffService;
        this.admin = admin;
        this.categories = categories;
        this.stylistLinks = stylistLinks;
        this.stylists = stylists;
        this.photos = photos;
        this.storage = storage;
        this.users = users;
        this.outbox = outbox;
        this.closures = closures;
    }

    /**
     * Session 6: {@code ownerUserId} comes from the caller's JWT ({@code AuthenticatedUser}),
     * never from the request body — the creating user always becomes this salon's OWNER via
     * a new salon_staff row, no invite needed for your own salon.
     */
    @Transactional
    public SalonResponse create(CreateSalonRequest req, UUID ownerUserId) {
        /*
         * ═══════════════════════════════════════════════════════════════════════════════════════
         * ONE SALON PER OWNER. Session 48 — this check did not exist.
         * ═══════════════════════════════════════════════════════════════════════════════════════
         * Nothing stopped the same account creating a salon over and over. It happened in testing
         * within a day of real use: the same phone and email produced two listings, and the
         * duplicate is not harmless — it splits the salon's reviews and bookings across two
         * records, doubles the moderation queue, and shows customers two entries for one shop with
         * no way to tell which is real.
         *
         * The cause is a familiar shape. The signup wizard only reaches this endpoint once, so the
         * "once" felt guaranteed by the UI. IT WAS NOT: the endpoint is reachable directly, and
         * more mundanely, an owner whose salon was rejected has every reason to try again from
         * scratch rather than find the resubmit button. A CONSTRAINT ENFORCED ONLY BY THE SHAPE OF
         * THE UI IS NOT ENFORCED.
         *
         * Rejected explicitly rather than silently returning the existing salon: an owner who
         * meant to create a second location needs to know we do not support that yet, and an owner
         * who is retrying a rejection needs pointing at resubmit. Both are actionable; a silent
         * no-op is not.
         *
         * Multi-location chains are a real future case, and this is deliberately the thing that
         * will have to change when we get there — a check in one place, with a message that says
         * what to do meanwhile.
         */
        if (staffService.ownsASalon(ownerUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SALON_ALREADY_EXISTS: this account already owns a salon. If it was rejected, "
                    + "open BMP and resubmit it rather than creating a new one. For a second "
                    + "location, contact us through the Help tab.");
        }

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
        s.setPincode(normalisePincode(req.pincode()));
        s.setAddress(req.address());
        s.setAbout(req.about());
        s.setImageUrl(req.imageUrl());
        /*
         * V015: NO storage key is accepted at creation time, and that is not an oversight.
         *
         * An upload key is `salons/{salonId}/...`, and the upload endpoint requires the caller to
         * already own that salon. At this moment the salon has no id — it is being created — so
         * no legitimate key for it can exist yet. Any key present in this request therefore
         * belongs to some OTHER salon, and there is nothing to validate it against.
         *
         * Silently ignoring it is the safe read: the salon is created with whatever imageUrl was
         * given (a pasted link works fine here), and the owner uploads a cover afterwards through
         * the update path, where the key CAN be checked. Accepting it would mean writing an
         * unverifiable pointer to someone else's file into a brand-new row — and later deleting
         * that file when the cover is replaced.
         */
        s.setImageStorageKey(null);
        // Where booking alerts go. Until Session 40 the salon was never told a customer had
        // booked — see the field's javadoc.
        s.setBookingNotifyEmail(req.bookingNotifyEmail());
        s.setBookingNotifyPhone(req.bookingNotifyPhone());

        /*
         * The human reference (BMPS001), allocated before the insert. V017 (Session 48).
         *
         * Before the save, so the row is never briefly visible without one — a NULL reference on a
         * live salon is a support call waiting to happen, and "we'll fill it in afterwards" is how
         * a column stays half-populated for a year.
         */
        s.assignReference(salons.allocateReference());

        s = salons.save(s);
        replaceCategories(s.getId(), req.categories());
        /*
         * ── THE RACE THE SERVICE CHECK CANNOT WIN. Session 65. ──────────────────────────────────
         *
         * `ownsASalon` above is a check-then-act: it reads, decides, and only then writes. Two
         * requests arriving together — a double-tapped button, a client retry — both read "no
         * salon", both pass, and both arrive here. V027's unique index is what actually stops the
         * second one, and it stops it HERE, as a constraint violation.
         *
         * Without this catch that surfaces as a 500 and an owner is told "something went wrong"
         * for a situation the system understands perfectly well. Translated to the SAME 409 and
         * the same wording as the check above, so the two paths are indistinguishable to the
         * person on the other end — which they should be, because they mean the same thing.
         *
         * This is the pattern in general: the application check exists to EXPLAIN, the constraint
         * exists to GUARANTEE, and the constraint's failure has to be translated back into the
         * explanation.
         */
        try {
            staffService.addOwner(s.getId(), ownerUserId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Concurrent salon creation blocked by uk_salon_staff_one_owner_seat: owner={}",
                    ownerUserId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SALON_ALREADY_EXISTS: this account already owns a salon. If it was rejected, "
                    + "open BMP and resubmit it rather than creating a new one. For a second "
                    + "location, contact us through the Help tab.");
        }

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

        publishSubmissionReceipt(s, ownerUserId);
        return toResponse(s);
    }

    /**
     * Tell the owner their application actually arrived. Session 48.
     *
     * <h2>The silence this fills</h2>
     * Creating a salon published NOTHING. The owner finished a four-step form, watched it submit,
     * and then heard from us only if and when a moderator got to it — which could be the next
     * working day. From their side that is indistinguishable from the form having failed, and the
     * rational response is to fill it in again, which is how duplicate salons reach the queue.
     *
     * <p>A receipt costs one email and removes the entire class of "did that work?" support
     * contact. It is also the natural place to set the expectation about review time.
     *
     * <h2>Why 'pending' and not a new status</h2>
     * This reuses {@link com.bmp.common.events.SalonStatusChanged} with the salon's real status,
     * which is exactly what it is: pending review. Inventing a 'submitted' status for the event
     * would mean the event and the database disagreed about the same salon, and every future
     * reader would have to learn that the two vocabularies differ.
     *
     * <h2>Never fatal</h2>
     * Same rule as the moderation-queue call above: bookkeeping failing must never cancel the
     * thing being booked. The salon is saved and queued; a failure to resolve the owner's contact
     * details is logged and the signup completes.
     */
    private void publishSubmissionReceipt(Salon s, UUID ownerUserId) {
        String ownerName = null;
        String email = null;
        String phone = null;
        try {
            var user = users.getUserById(ownerUserId).getBody();
            if (user != null) {
                ownerName = user.name();
                email = user.email();
                phone = user.phone();
            }
        } catch (Exception e) {
            // Publish anyway. The dispatcher logs "no contact details" — recoverable and visible.
            // Dropping the event would leave no trace that anyone was ever owed this email.
            log.warn("Could not resolve owner {} of new salon {} for the submission receipt ({}). "
                    + "Publishing without contact details.", ownerUserId, s.getId(), e.toString());
        }

        try {
            outbox.publish(new com.bmp.common.events.SalonStatusChanged(
                    s.getId(), s.getReference(), s.getName(), STATUS_PENDING, ownerUserId,
                    ownerName, email, phone,
                    null,
                    // Nothing to resubmit — it has only just been submitted for the first time.
                    false));
        } catch (Exception e) {
            log.error("Salon {} was created but its submission receipt could not be queued ({}). "
                    + "The owner will not get the 'we've got your application' email; the salon "
                    + "itself is fine and in the review queue.", s.getId(), e.toString());
        }
    }

    /**
     * Trim, and turn blank into null. Session 48.
     *
     * <h2>Why blank must become null</h2>
     * V016's CHECK accepts NULL or exactly six digits — it does NOT accept the empty string. A
     * form that submits an untouched optional field sends {@code ""}, which would hit the database
     * as a constraint violation and surface to the owner as a 500 on an unrelated save. Null is
     * what "they didn't fill it in" actually means.
     *
     * <p>Deliberately does NOT validate the six-digit shape here. The constraint is the authority,
     * and duplicating the rule in Java gives us two places to disagree; the client validates for a
     * friendly message, the database validates for correctness. What this method fixes is the one
     * case where the two disagree about the SAME value being absent.
     */
    private static String normalisePincode(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Set the platform's commission for a salon. Session 48.
     *
     * <h2>Who may call this</h2>
     * Only bmp-admin, through the service-key-guarded internal status endpoint, at the moment a
     * moderator approves the salon. It is deliberately NOT reachable from the owner-facing policy
     * endpoint — {@code SalonPolicyUpdate} has no commission field, and that omission is load
     * bearing: an owner who could set their own rate would set it to zero.
     *
     * <h2>Why it creates the policy row if missing</h2>
     * A salon approved before it ever opened its policy editor has no {@code salon_policy} row
     * yet. Silently doing nothing in that case would be the worst outcome — the console would show
     * the agreed rate, the database would hold the 12% default, and the discrepancy would only
     * surface in a payout dispute months later. Better to create the row with the platform
     * defaults and the agreed rate on it.
     *
     * @param bps null is a no-op, meaning "leave the rate alone"
     */
    @Transactional
    public void setCommissionBps(UUID salonId, Integer bps) {
        if (bps == null) return;
        if (bps < 0 || bps > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "INVALID_COMMISSION_BPS: expected basis points between 0 and 5000 (1200 = 12%).");
        }
        SalonPolicy p = policies.findBySalonId(salonId)
                .orElseGet(() -> new SalonPolicy(salonId, "standard", 24, 15, false, 15));
        p.setCommissionBps(bps);
        p.touch();
        policies.save(p);
        log.info("Salon {} commission set to {} bps by the staff console", salonId, bps);
    }

    /**
     * The owner opens their doors: approved -> active. Session 48.
     *
     * <h2>Why this is the owner's call and not the moderator's</h2>
     * Approval is a judgement about whether the salon is legitimate. Being ready to take bookings
     * is a completely different question, and only the owner can answer it — they know whether the
     * service menu is right, whether the hours are set, whether their stylists have been briefed.
     * Conflating the two is what produced approved salons in search results with an empty menu.
     *
     * <h2>Refuses from every other status, with a reason</h2>
     * Not silently: an owner pressing a button that appears to do nothing will press it again,
     * then contact support. Each refusal names the actual state so the message is actionable.
     *
     * <p>Idempotent for a salon that is already live — pressing Go Live twice is not an error, it
     * is a double-click, and turning that into a 409 would be a support ticket about a button.
     */
    @Transactional
    public SalonApprovalResponse goLive(UUID salonId) {
        Salon s = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        if (STATUS_ACTIVE.equals(s.getStatus())) {
            return approvalOf(s);   // already live — see the javadoc
        }
        if (!STATUS_APPROVED.equals(s.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, switch (s.getStatus()) {
                case STATUS_PENDING -> "NOT_APPROVED_YET: your salon is still being reviewed. "
                        + "We'll email you the moment there's a decision.";
                case "rejected" -> "REJECTED: fix the points in our email and resubmit — you can "
                        + "go live once the salon is approved.";
                case "suspended" -> "SUSPENDED: this salon is suspended. Contact us through the "
                        + "Help tab and we'll talk it through.";
                default -> "CANNOT_GO_LIVE: unexpected status '" + s.getStatus() + "'.";
            });
        }

        /*
         * A live salon with nothing to sell is the exact failure this whole change exists to
         * prevent, so it is blocked here rather than allowed and regretted. Checked at the moment
         * of publishing, not continuously: a salon that later archives every service has other
         * problems and is not silently unpublished behind the owner's back.
         */
        boolean hasLiveService = services.findBySalonId(salonId).stream().anyMatch(x -> !x.isArchived());
        if (!hasLiveService) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "NO_SERVICES: add at least one service with a price before going live — "
                    + "customers book a service, not a salon.");
        }

        s.setStatus(STATUS_ACTIVE);
        s.markWentLive();
        s.touch();
        salons.save(s);
        log.info("Salon {} went LIVE (owner action)", salonId);
        return approvalOf(s);
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
        if (req.pincode() != null) s.setPincode(normalisePincode(req.pincode()));
        if (req.address() != null) s.setAddress(req.address());
        if (req.about() != null) s.setAbout(req.about());
        if (req.imageUrl() != null) {
            // Cover image replaced: drop the file the old one pointed at, if it was ours.
            String supersededCoverKey = s.getImageStorageKey();
            s.setImageUrl(req.imageUrl());
            // `id`, not `salonId` — this method's parameter is named `id`, unlike addPhoto /
            // addService / updateService where it is `salonId`. Writing `salonId` here was a
            // compile error, and the four sibling call sites reading `salonId` are correct.
            s.setImageStorageKey(requireOwnKeyOrNull(id, req.imageStorageKey()));
            deleteObjectQuietly(supersededCoverKey);
        }
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

        // V012 (Session 44): live services only. This line read `findBySalonId(salonId)` and
        // would have shown retired services on the customer's salon page — and worse, fed them
        // into startingPrice(), so a salon that retired its cheapest service would still be
        // advertised "from ₹350". Archiving must reach every customer-facing surface, not just
        // the one the owner is looking at when they press the button.
        List<com.bmp.salon.entities.SalonService> menu = services.findBySalonId(salonId).stream()
                .filter(svc -> !svc.isArchived())
                .toList();

        return new SalonDetailResponse(
                s.getId(), s.getName(), parseLocation(s.getLocation()), s.getStatus(),
                s.getArea(), s.getPincode(), s.getAddress(), s.getAbout(), s.getImageUrl(),
                s.getRating(), s.getReviewCount(),
                categoriesOf(salonId),
                startingPrice(menu),
                openHoursLabel(salonId),
                // V022 — so the customer's date picker only offers days this salon will accept.
                // Defaults to 30 for a salon with no policy row: the behaviour before V022.
                policies.findBySalonId(salonId).map(SalonPolicy::getBookingHorizonDays).orElse(30),
                menu.stream().map(this::toServiceResponse).toList(),
                publicStylists(salonId),
                // V014 (Session 44) — the gallery, in the same call. See the record's javadoc.
                listPhotos(salonId),
                // Session 64 — tell the customer the salon is SHUT, not merely fully booked.
                closureNotice(salonId));
    }

    /**
     * A closure covering right now, or the soonest one starting within the next 7 days.
     * Null when neither applies — see the field javadoc on {@link SalonDetailResponse}.
     */
    private ClosureNotice closureNotice(UUID salonId) {
        java.time.Instant now = java.time.Instant.now();
        List<com.bmp.salon.entities.SalonClosure> upcoming =
                closures.findActiveOverlapping(salonId, now, now.plus(7, java.time.temporal.ChronoUnit.DAYS));
        if (upcoming.isEmpty()) return null;
        com.bmp.salon.entities.SalonClosure next = upcoming.get(0);
        return new ClosureNotice(next.covers(now), next.getStartsAt(), next.getEndsAt(), next.getReason());
    }

    // ── V014 (Session 44): the salon gallery ─────────────────────────────────────────────────
    //
    // Public read, owner/manager write. The read is public on purpose and unlike the combos
    // controller: photos exist ONLY to be seen by customers deciding whether to book, so gating
    // them would defeat the feature. Contrast combos, whose reads are restricted precisely
    // because no customer screen consumes them yet.

    /** Ordered by the owner's choice. Public — this is what a customer browses. */
    public List<SalonPhotoResponse> listPhotos(UUID salonId) {
        return photos.findBySalonIdOrderBySortOrderAscCreatedAtAsc(salonId).stream()
                .map(this::toPhotoResponse)
                .toList();
    }

    /**
     * The owner's own approval status, and on rejection what to fix. Session 46.
     *
     * <h2>The gap this closes</h2>
     * Until now <b>an owner was never told their status</b>. The login response carried none, and
     * {@code OwnerDashboard} rendered a full working desk regardless — so a PENDING owner and a
     * REJECTED owner both got a screen that looked live. They would build a service menu, invite
     * staff, set opening hours, and wait for bookings that could never arrive, because customers
     * cannot see a salon that isn't approved. A failure that looks exactly like success.
     *
     * <h2>Degrades rather than fails</h2>
     * If bmp-admin is unreachable this returns the salon's own {@code status} column with no
     * review detail, instead of erroring. That column is written by the moderation decision
     * itself, so it is accurate even when the service that owns the reasoning is down — and an
     * owner seeing "approved" with no extra detail is far better than an owner seeing an error
     * page and concluding the platform is broken.
     */
    /**
     * The approval view when we have only the salon row — no moderation record to add.
     *
     * <p>Used for a salon that was never enqueued, for a bmp-admin outage, and after Go Live.
     * Exists so the two live/can-go-live flags are computed in exactly ONE place; deriving them at
     * each construction site is how two of the three end up disagreeing after a status is added.
     */
    private SalonApprovalResponse approvalOf(Salon s) {
        return new SalonApprovalResponse(
                s.getStatus(), null, null, null, null, 0, false,
                STATUS_ACTIVE.equals(s.getStatus()),
                STATUS_APPROVED.equals(s.getStatus()));
    }

    public SalonApprovalResponse approvalStatus(UUID salonId) {
        Salon s = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        try {
            var body = admin.latestReview(salonId).getBody();
            if (body != null) {
                return new SalonApprovalResponse(
                        s.getStatus(), body.status(), body.decisionNote(),
                        body.submittedAt(), body.decidedAt(), body.submissionCount(),
                        // Only a rejection is the owner's to act on. An approved or suspended
                        // salon has nothing to resubmit — see SalonModerationService.resubmit.
                        "rejected".equals(body.status()),
                        STATUS_ACTIVE.equals(s.getStatus()),
                        STATUS_APPROVED.equals(s.getStatus()));
            }
            // 204 — never submitted. Real state: created before the queue existed, or an enqueue
            // that failed. Reported honestly rather than guessed at.
            return approvalOf(s);
        } catch (Exception e) {
            log.warn("Could not read moderation review for salon {} ({}). Falling back to the "
                    + "salon's own status column.", salonId, e.toString());
            return approvalOf(s);
        }
    }

    /**
     * Resubmit a rejected salon for review, after the owner has fixed what was wrong.
     *
     * <p>bmp-salon has already checked this caller owns this salon ({@code @PreAuthorize} on the
     * controller); bmp-admin checks the salon is actually in a resubmittable state. Neither takes
     * the other's word for its own half of the question.
     */
    @Transactional
    public SalonApprovalResponse resubmitForReview(UUID salonId, String note) {
        salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        var review = admin.resubmit(salonId, new com.bmp.salon.client.AdminServiceClient.ResubmitRequest(note));

        // The salon's own status column has to move back to pending too, or the salon stays
        // 'rejected' here while bmp-admin believes it is queued — and the two disagree about
        // whether customers should see it.
        Salon s = salons.findById(salonId).orElseThrow();
        s.setStatus(STATUS_PENDING);
        salons.save(s);

        log.info("Salon {} resubmitted for review (submission #{})", salonId, review.submissionCount());
        return new SalonApprovalResponse(STATUS_PENDING, review.status(), null,
                review.submittedAt(), null, review.submissionCount(), false, false, false);
    }

    /**
     * Set the salon's map pin. Session 45 — owner or manager.
     *
     * <p>Deliberately narrow: it writes {@code location} and nothing else. The whole-profile
     * {@code update} is owner-only because it can rename the business; this exists so a manager
     * can fix the pin without being handed that. See {@code SalonController.updateLocation}.
     *
     * <p>Stored as the {@code "lat,lng"} string the rest of this service reads — see the class
     * header on why there is no hibernate-spatial here yet. Range and Null-Island checks happen
     * at the controller, before anything gets this far.
     */
    @Transactional
    public SalonResponse updateLocation(UUID salonId, LatLng location) {
        Salon s = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        s.setLocation(location.lat() + "," + location.lng());
        log.info("Salon {} map position updated to {},{}", salonId, location.lat(), location.lng());
        return toResponse(salons.save(s));
    }

    /**
     * Add a photo to the gallery.
     *
     * <p>Capped at {@link #MAX_SALON_PHOTOS}. Not arbitrary: the gallery is rendered in full on
     * a page that customers open on mobile data, and an unbounded list is a salon accidentally
     * making its own page unusable. A cap the owner is told about beats a page that mysteriously
     * crawls.
     */
    @Transactional
    public SalonPhotoResponse addPhoto(UUID salonId, SalonPhotoRequest req) {
        salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        long existing = photos.countBySalonId(salonId);
        if (existing >= MAX_SALON_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PHOTO_LIMIT_REACHED: a salon can show up to " + MAX_SALON_PHOTOS
                    + " photos. Remove one to add another.");
        }

        com.bmp.salon.entities.SalonPhoto photo = new com.bmp.salon.entities.SalonPhoto(
                salonId,
                requireHttpUrl(req.url()),
                // Null for a pasted link; a validated key for an upload. See requireOwnKeyOrNull.
                requireOwnKeyOrNull(salonId, req.storageKey()),
                trimToNull(req.caption()),
                // Default to the end of the list rather than 0 — a new photo jumping to the front
                // would silently demote the one the owner chose to lead with.
                req.sortOrder() != null ? req.sortOrder() : (int) existing);
        log.info("Photo added to salon {} ({} of {})", salonId, existing + 1, MAX_SALON_PHOTOS);
        return toPhotoResponse(photos.save(photo));
    }

    /**
     * Edit a photo's caption, position or image. Null means unchanged.
     *
     * <p><b>Replacing the image deletes the old file.</b> Without that, every re-upload leaves an
     * object nothing will ever reference again — invisible, unbounded, and paid for monthly. The
     * old key is captured BEFORE the entity is mutated, because after {@code setStorageKey} there
     * is no longer any record of what to delete.
     */
    @Transactional
    public SalonPhotoResponse updatePhoto(UUID salonId, UUID photoId, SalonPhotoRequest req) {
        com.bmp.salon.entities.SalonPhoto photo = requirePhotoOfSalon(salonId, photoId);

        String supersededKey = null;
        if (req.url() != null && !req.url().isBlank()) {
            // Only OUR keys are candidates for deletion — a null key means the salon hosts that
            // image and it is not ours to remove.
            supersededKey = photo.getStorageKey();
            photo.setUrl(requireHttpUrl(req.url()));
            photo.setStorageKey(requireOwnKeyOrNull(salonId, req.storageKey()));
        }
        if (req.caption() != null) photo.setCaption(trimToNull(req.caption()));
        if (req.sortOrder() != null) photo.setSortOrder(req.sortOrder());

        SalonPhotoResponse saved = toPhotoResponse(photos.save(photo));
        // After the save, so a failed write never deletes a file the row still points at.
        deleteObjectQuietly(supersededKey);
        return saved;
    }

    /**
     * Remove a photo. A genuine DELETE, unlike a service.
     *
     * <p>Nothing references a photo — no booking snapshots it, no other table points at it — so
     * there is no history to orphan and nothing to archive. The contrast with
     * {@link #archiveService} is the whole reason that one is an archive: it isn't squeamishness
     * about deletion, it's that a service row is referenced and a photo row isn't.
     */
    @Transactional
    public void deletePhoto(UUID salonId, UUID photoId) {
        com.bmp.salon.entities.SalonPhoto photo = requirePhotoOfSalon(salonId, photoId);
        String key = photo.getStorageKey();   // captured before the entity goes
        photos.delete(photo);
        // Row first, file second. If this fails we leak an object, which is recoverable by
        // reconciliation (see V015's index comments); the reverse order would leave a row
        // pointing at an image that no longer exists, which shows a customer a broken picture.
        deleteObjectQuietly(key);
        log.info("Photo {} removed from salon {}{}", photoId, salonId,
                key != null ? " (uploaded file deleted)" : " (external link, no file to delete)");
    }

    /**
     * Remove a photo because moderation upheld a report about it. Session 60.
     *
     * <h2>Why this exists beside {@link #deletePhoto}</h2>
     * A moderator has a photo id from a content report and no salon id — the report names the
     * content, not its owner. {@code deletePhoto} takes both and uses the pair to prove ownership,
     * which is exactly right for an OWNER acting on their own gallery and useless here.
     *
     * <p>The check it drops is not a check a moderator needs: their authority is platform-wide, so
     * "does this photo belong to the salon you claim" has no meaning. What replaces it is that this
     * method is reachable only through a {@code ROLE_SERVICE} endpoint called by bmp-admin, which
     * has already checked {@code content:moderate}.
     *
     * @return the salon the photo belonged to, so the caller can log and audit against a business
     *         rather than an orphan id
     */
    @Transactional
    public UUID removePhotoByModerator(UUID photoId, String reason) {
        com.bmp.salon.entities.SalonPhoto photo = photos.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND"));
        UUID salonId = photo.getSalonId();
        String key = photo.getStorageKey();
        photos.delete(photo);
        deleteObjectQuietly(key);
        log.warn("Photo {} removed from salon {} by MODERATION — {}", photoId, salonId, reason);
        return salonId;
    }

    /**
     * Load a photo and prove it belongs to this salon.
     *
     * <p>Same reasoning as {@code requireServiceOfSalon}: {@code @PreAuthorize} checks the CALLER
     * owns {@code salonId} and never looks at {@code photoId}, so without this an owner could
     * delete another salon's photos by id. <b>Authorise the path, then trust the body</b> — the
     * Session 40 shape, and it recurs on every nested resource.
     */
    private com.bmp.salon.entities.SalonPhoto requirePhotoOfSalon(UUID salonId, UUID photoId) {
        com.bmp.salon.entities.SalonPhoto photo = photos.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND"));
        if (!photo.getSalonId().equals(salonId)) {
            log.warn("Salon {} tried to touch photo {}, which belongs to salon {}",
                    salonId, photoId, photo.getSalonId());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
        }
        return photo;
    }

    private SalonPhotoResponse toPhotoResponse(com.bmp.salon.entities.SalonPhoto p) {
        return new SalonPhotoResponse(p.getId(), p.getSalonId(), p.getUrl(), p.getCaption(),
                p.getSortOrder(), p.getCreatedAt());
    }

    /**
     * Accept a storage key ONLY if it belongs to this salon. Null in, null out.
     *
     * <p>The upload endpoint already checks that the caller owns the salon it uploaded to. This
     * checks something different and equally necessary: that the key now being ATTACHED to a row
     * is one from that same salon. Without it, an owner could upload to their own salon, then
     * pass the returned key when writing a row for another salon — the upload was authorised, the
     * use was not. <b>Authorise the path, then trust the body</b> is the recurring hole in this
     * codebase, and a two-step upload is exactly the shape that invites it.
     *
     * <p>Keys are built as {@code salons/{salonId}/{purpose}/{uuid}.jpg} by
     * {@code ImageIngest.key}, so the prefix check below is the same fact the key was built from.
     */
    private static String requireOwnKeyOrNull(UUID salonId, String rawKey) {
        String key = trimToNull(rawKey);
        if (key == null) return null;               // pasted link — nothing to own
        String expectedPrefix = "salons/" + salonId + "/";
        if (!key.startsWith(expectedPrefix)) {
            log.warn("Salon {} tried to attach storage key '{}', which is not under its own prefix",
                    salonId, key);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "STORAGE_KEY_MISMATCH: that image doesn't belong to this salon.");
        }
        return key;
    }

    /**
     * Delete every uploaded image belonging to a rejected salon. Session 48.
     *
     * <h2>The trade this settles</h2>
     * Photos stay during review on purpose — a reviewer judging what a customer would actually see
     * approves faster and more consistently than one looking at a name and an address. But a
     * rejected salon's files have no remaining purpose, and object storage that only ever grows is
     * a bill nobody notices until it is large.
     *
     * <p>Only UPLOADED objects are touched. A salon whose cover is a pasted URL has nothing of
     * ours to delete, and the database rows are left alone so a resubmitting owner still sees
     * their gallery entries and can re-upload rather than starting from a blank screen.
     *
     * <p>Never fatal: this runs inside the moderation decision, and a storage outage must not
     * prevent a salon being rejected. A leaked object is findable later; a moderation decision
     * that silently failed is not.
     */
    @Transactional
    public void purgeUploadedImagesOnRejection(UUID salonId) {
        int deleted = 0;
        try {
            Salon s = salons.findById(salonId).orElse(null);
            if (s != null && s.getImageStorageKey() != null) {
                deleteObjectQuietly(s.getImageStorageKey());
                s.setImageStorageKey(null);
                salons.save(s);
                deleted++;
            }
            for (var photo : photos.findBySalonIdOrderBySortOrderAscCreatedAtAsc(salonId)) {
                if (photo.getStorageKey() != null) {
                    deleteObjectQuietly(photo.getStorageKey());
                    deleted++;
                }
            }
            log.info("Salon {} was rejected — purged {} uploaded image(s) from storage.", salonId, deleted);
        } catch (Exception e) {
            log.warn("Could not purge uploaded images for rejected salon {} ({}). The rejection "
                    + "itself is unaffected; these objects are now orphaned and will need the "
                    + "storage sweep.", salonId, e.toString());
        }
    }

    /**
     * Delete an uploaded object, ignoring null and never throwing.
     *
     * <p>Callers reach here after the database row has already been written or removed — the
     * user's action has succeeded and there is nothing useful to tell them. Turning a storage
     * hiccup into a failed request would report "delete failed" for a photo that is, in fact,
     * gone. A leaked object costs a fraction of a rupee and is findable later.
     */
    private void deleteObjectQuietly(String key) {
        if (key == null) return;
        storage.delete(key);
    }

    /** A required variant of {@link #requireHttpUrlOrNull} — a photo with no URL is not a photo. */
    private static String requireHttpUrl(String raw) {
        String url = requireHttpUrlOrNull(raw);
        if (url == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHOTO_URL_REQUIRED");
        }
        return url;
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

    /** Public since Session 48 — the moderation packet needs it too. */
    public List<String> categoriesOfSalon(UUID salonId) { return categoriesOf(salonId); }

    /**
     * The service menu, as a reviewer needs to see it. Session 48.
     *
     * <p>Includes ARCHIVED services, unlike every customer-facing path. A reviewer judging whether
     * a salon is real benefits from seeing the whole menu, and an archived row is a fact about the
     * business rather than something to hide from them — it is flagged rather than filtered.
     */
    public List<com.bmp.salon.controllers.InternalSalonController.ModerationServiceItem>
            servicesForModeration(UUID salonId) {
        return services.findBySalonId(salonId).stream()
                .map(x -> new com.bmp.salon.controllers.InternalSalonController.ModerationServiceItem(
                        x.getName(), x.getPricePaise().paise(), x.getDurationMinutes(), x.isArchived()))
                .toList();
    }

    /** The gallery, for the review screen — the single most useful thing on it. */
    public List<com.bmp.salon.controllers.InternalSalonController.ModerationPhoto>
            photosForModeration(UUID salonId) {
        return photos.findBySalonIdOrderBySortOrderAscCreatedAtAsc(salonId).stream()
                .map(x -> new com.bmp.salon.controllers.InternalSalonController.ModerationPhoto(
                        x.getUrl(), x.getCaption()))
                .toList();
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
        return near(lat, lng, radiusKm, category, null);
    }

    /**
     * Nearby salons, optionally filtered by category and a free-text query. Session 45.
     *
     * <h2>Why the query has to run HERE and not in the app</h2>
     * The frontend already filtered on name and area — over the list it had already downloaded,
     * which is the list of salons within the radius. So searching for a salon 9km away found
     * nothing, and the failure looked like "that salon isn't on BMP" rather than "widen your
     * search". Filtering a page you already fetched isn't search; it's a highlight.
     *
     * <h2>Searching by SERVICE is the point</h2>
     * "Balayage", "keratin", "bridal makeup" — a customer knows what they want done and has no
     * idea which salon does it. That is the query the whole product exists to answer, and until
     * now it matched nothing at all: service names were never searched, anywhere. Matching them
     * here also means a salon is found by what it SELLS, which is the thing an owner controls.
     *
     * <p>Archived services are excluded from the match for the same reason they're excluded from
     * the "from ₹X" price: finding a salon by a service it no longer offers is a promise nobody
     * can keep.
     */
    public List<NearbySalonResponse> near(double lat, double lng, double radiusKm,
                                          String category, String query) {
        List<Salon> visible = salons.findByStatusIn(PUBLICLY_VISIBLE);

        if (category != null && !category.isBlank()) {
            java.util.Set<UUID> matching =
                    new java.util.HashSet<>(categories.findSalonIdsByCategory(category.trim()));
            visible = visible.stream().filter(s -> matching.contains(s.getId())).toList();
        }

        String q = query == null ? null : query.trim().toLowerCase();
        if (q != null && !q.isEmpty()) {
            // Salons whose LIVE service menu mentions the query. Computed before the name/area
            // check so a salon matching only on a service still survives.
            java.util.Set<UUID> byService = services.findBySalonIdIn(
                            visible.stream().map(Salon::getId).toList()).stream()
                    .filter(x -> !x.isArchived())
                    .filter(x -> x.getName() != null && x.getName().toLowerCase().contains(q))
                    .map(com.bmp.salon.entities.SalonService::getSalonId)
                    .collect(Collectors.toSet());

            visible = visible.stream()
                    .filter(s -> (s.getName() != null && s.getName().toLowerCase().contains(q))
                            || (s.getArea() != null && s.getArea().toLowerCase().contains(q))
                            || (s.getAddress() != null && s.getAddress().toLowerCase().contains(q))
                            // V016 (Session 48) — PIN code is how people in Bengaluru actually
                            // say where they are. `equals`, not `contains`: a PIN is an exact
                            // identifier, and a substring match would make "560" return a third
                            // of the city, which is not a search result anybody wanted.
                            || (s.getPincode() != null && s.getPincode().equals(q))
                            || byService.contains(s.getId()))
                    .toList();
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

        // V012 (Session 44): archived services must not set the "from ₹X" on a search result.
        // Second place this filter is needed, and the second place it would have been silently
        // wrong — the "from" price is the number a customer decides on before they ever open the
        // salon page, so a retired service quoting the cheapest price is a promise nobody can keep.
        Map<UUID, Long> cheapestBySalon = services.findBySalonIdIn(ids).stream()
                .filter(x -> !x.isArchived())
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
            /*
             * Session 65 — was a silent null. A salon whose stored location cannot be parsed loses
             * its map pin with no trace anywhere, and "no location set" then looks like a salon
             * that never set one rather than data we failed to read. The fallback is still null —
             * a broken pin must not fail the whole salon page — but now it leaves a breadcrumb.
             */
            log.warn("Could not parse a salon location from {} — the map pin will be missing ({})",
                    raw, e.toString());
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
         * ── V022: the booking window (Session 49) ─────────────────────────────────────────────
         *
         * Null means "leave it alone", the same rule the V010 fields follow. A client that
         * doesn't know about these fields must not reset a salon's carefully chosen horizon to a
         * default by saving an unrelated change.
         *
         * Bounds mirror V022's CHECK constraints so the owner gets a 400 naming the problem
         * rather than a 500 carrying a constraint name.
         */
        if (req.bookingHorizonDays() != null) {
            int d = req.bookingHorizonDays();
            if (d < 1 || d > 365) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "BOOKING_HORIZON_OUT_OF_RANGE: between 1 and 365 days. 1 means customers "
                        + "can book today and tomorrow.");
            }
            p.setBookingHorizonDays(d);
        }
        if (req.minNoticeMinutes() != null) {
            int m = req.minNoticeMinutes();
            if (m < 0 || m > 10080) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "MIN_NOTICE_OUT_OF_RANGE: between 0 and 10080 minutes (7 days). 0 means "
                        + "customers can book a slot starting right now.");
            }
            p.setMinNoticeMinutes(m);
        }

        /*
         * The combination that silently closes a salon.
         *
         * Notice of 3 days with a horizon of 2 days means every bookable slot is either too soon
         * or past the horizon: the calendar is empty and nothing in the UI explains why. Caught
         * here, because an owner who has done this will report it as "customers can't book us"
         * and nobody will connect it to two numbers they set last week.
         */
        if (p.getMinNoticeMinutes() >= p.getBookingHorizonDays() * 24 * 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BOOKING_WINDOW_EMPTY: a minimum notice of " + p.getMinNoticeMinutes()
                    + " minutes leaves nothing bookable inside a " + p.getBookingHorizonDays()
                    + "-day window. Lower the notice or open the window further out.");
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
        /*
         * ═══════════════════════════════════════════════════════════════════════════════════════
         * COMMISSION IS NOT SETTABLE HERE. Session 48 — this was a live money hole.
         * ═══════════════════════════════════════════════════════════════════════════════════════
         * This block used to write `p.setCommissionBps(req.commissionBps())`. The endpoint that
         * reaches it is POST /api/v1/salons/{salonId}/policy, guarded by
         *
         *     hasRole('SALON_OWNER') and principal.salonId().equals(#salonId)
         *
         * — which is correct about WHO may call it and says nothing about WHAT they may send. A
         * salon owner could therefore set their own platform commission to 0 with a single curl.
         *
         * The codebase believed it was protected: SalonPolicyUpdate on the frontend omits the
         * field and its javadoc says "an owner must not be able to set their own commission by
         * adding a field". But that is a TYPESCRIPT type. It constrains our own client and
         * nothing else. THE GUARD WAS IN THE CALLER, WHICH IS THE ONE PLACE A GUARD CANNOT LIVE.
         *
         * This is the same shape as Session 40's status hole and Session 29's public-paths
         * default: AUTHORISE THE PATH, THEN TRUST THE BODY. It keeps recurring because the
         * authorization annotation looks like the whole answer.
         *
         * Rejected loudly rather than ignored — a silently dropped field is how somebody concludes
         * the endpoint is broken and goes looking for another way in. The real path is bmp-admin's
         * approval decision, which lands in setCommissionBps() and records who decided.
         */
        if (req.commissionBps() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "COMMISSION_NOT_SELF_SERVICE: a salon's commission is agreed with BMP and set "
                    + "by our team, not through this endpoint.");
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
    /**
     * The salon's configured hours. A day with no row is CLOSED — absence is the signal, which is
     * why this returns only what exists rather than seven entries with nulls.
     */
    public HoursResponse getHours(UUID salonId) {
        salons.findById(salonId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));
        return new HoursResponse(salonId, hours.findBySalonId(salonId).stream()
                .map(h -> new HourEntry(h.getDayOfWeek(), h.getOpenTime(), h.getCloseTime()))
                .sorted(java.util.Comparator.comparingInt(HourEntry::dayOfWeek))
                .toList());
    }

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

        /*
         * ══════════════════════════════════════════════════════════════════════════════════════
         * SESSION 51 — DAYS NOT SENT ARE CLOSED. This method did not do that, and its own
         * @Operation summary says "Set opening hours for all 7 days at once".
         * ══════════════════════════════════════════════════════════════════════════════════════
         * It only ever upserted. A salon that opened on Sunday and then decided to close on
         * Sundays had no way to say so: omitting Sunday left the old row untouched, and the
         * availability algorithm reads presence-of-row as "open". The endpoint could open a day
         * and could never close one.
         *
         * A day being CLOSED is expressed by having no row — that is what
         * `findBySalonIdAndDayOfWeek` returning empty means to AvailabilityService. So the
         * replace-all semantic the summary promised is also the correct one, and this makes the
         * behaviour match the contract rather than the other way round.
         *
         * Deleting only within THIS salon and only days the caller omitted; nothing else is
         * touched.
         */
        java.util.Set<Integer> sent = req.hours().stream()
                .map(HourEntry::dayOfWeek).collect(java.util.stream.Collectors.toSet());
        for (SalonHours existing : hours.findBySalonId(salonId)) {
            if (!sent.contains(existing.getDayOfWeek())) {
                log.info("Salon {} is now CLOSED on day {} — the row is removed, which is how a "
                        + "closed day is expressed.", salonId, existing.getDayOfWeek());
                hours.delete(existing);
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
        // V011/V013 — set after construction: the constructor predates these columns and adding
        // parameters would break every other caller for fields that are all optional.
        svc.setCategory(req.category());
        svc.setDescription(trimToNull(req.description()));
        svc.setImageUrl(requireHttpUrlOrNull(req.imageUrl()));
        svc.setImageStorageKey(requireOwnKeyOrNull(salonId, req.imageStorageKey()));
        svc = services.save(svc);
        return toServiceResponse(svc);
    }

    /**
     * The salon's menu.
     *
     * @param includeArchived false (the customer view) hides retired services; true (the owner's
     *        Services tab) shows them, greyed, so they can be brought back. V012.
     *
     * <p>Defaulting to <b>false</b> at every call site that isn't the owner's editor is the point:
     * the failure that matters is a retired service still being bookable, and that failure should
     * require someone to have asked for it explicitly.
     */
    public List<ServiceResponse> listServices(UUID salonId, boolean includeArchived) {
        return services.findBySalonId(salonId).stream()
                .filter(s -> includeArchived || !s.isArchived())
                .map(this::toServiceResponse)
                .toList();
    }

    /** Back-compat for the callers that only ever want the live menu. */
    public List<ServiceResponse> listServices(UUID salonId) {
        return listServices(salonId, false);
    }

    /**
     * Edit a service. V012 (Session 44).
     *
     * <p>Null means unchanged, per {@code UpdateServiceRequest}. Safe against history by
     * construction — {@code booking_service_item} froze name/price/duration when each booking was
     * made, so nothing here can rewrite what a customer already agreed to pay.
     *
     * <p>An archived service can still be edited (fix a typo before restoring it); editing does
     * not un-archive, because those are two different intentions and guessing at one from the
     * other is how a retired service quietly reappears on the menu.
     */
    @Transactional
    public ServiceResponse updateService(UUID salonId, UUID serviceId, UpdateServiceRequest req) {
        com.bmp.salon.entities.SalonService svc = requireServiceOfSalon(salonId, serviceId);

        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SERVICE_NAME_REQUIRED");
            }
            svc.setName(req.name().trim());
        }
        if (req.pricePaise() != null) {
            if (req.pricePaise() < 0) {
                // Money is unsigned by contract; a negative price would flow into a booking
                // snapshot and then into a payout calculation.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRICE_CANNOT_BE_NEGATIVE");
            }
            svc.setPricePaise(Money.ofPaise(req.pricePaise()));
        }
        if (req.durationMinutes() != null) {
            if (req.durationMinutes() <= 0) {
                // Duration drives every bookable slot. A zero-minute service makes the
                // availability algorithm produce infinite openings.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DURATION_MUST_BE_POSITIVE");
            }
            svc.setDurationMinutes(req.durationMinutes());
        }
        if (req.requiresStylistAssignment() != null) {
            svc.setRequiresStylistAssignment(req.requiresStylistAssignment());
        }
        if (req.category() != null) {
            // Empty string clears the grouping (back to "Other"); null left it alone above.
            svc.setCategory(req.category().isBlank() ? null : req.category().trim());
        }
        // V013. Same null-means-unchanged / empty-means-clear rule as category.
        if (req.description() != null) {
            svc.setDescription(trimToNull(req.description()));
        }
        String supersededServiceImageKey = null;
        if (req.imageUrl() != null) {
            // Captured BEFORE the setter, because afterwards there is no record of what to
            // delete. Same ordering as updatePhoto and for the same reason.
            supersededServiceImageKey = svc.getImageStorageKey();
            svc.setImageUrl(requireHttpUrlOrNull(req.imageUrl()));
            svc.setImageStorageKey(requireOwnKeyOrNull(salonId, req.imageStorageKey()));
        }

        log.info("Service {} updated at salon {}", serviceId, salonId);
        ServiceResponse saved = toServiceResponse(services.save(svc));
        // Only after the row is safely written — a failed save must never delete a file the
        // row still points at.
        deleteObjectQuietly(supersededServiceImageKey);
        return saved;
    }

    /**
     * Retire a service. V012 (Session 44) — <b>not a delete</b>; see the migration's header for
     * the three separate ways deleting this row causes damage.
     */
    @Transactional
    public ServiceResponse archiveService(UUID salonId, UUID serviceId) {
        com.bmp.salon.entities.SalonService svc = requireServiceOfSalon(salonId, serviceId);
        svc.archive();
        log.info("Service {} archived at salon {} — off the menu, existing bookings unaffected",
                serviceId, salonId);
        return toServiceResponse(services.save(svc));
    }

    /** Put a retired service back on the menu. */
    @Transactional
    public ServiceResponse restoreService(UUID salonId, UUID serviceId) {
        com.bmp.salon.entities.SalonService svc = requireServiceOfSalon(salonId, serviceId);
        svc.restore();
        log.info("Service {} restored at salon {}", serviceId, salonId);
        return toServiceResponse(services.save(svc));
    }

    /**
     * Load a service and prove it belongs to this salon.
     *
     * <p>The salon-scoped lookup is the security boundary, not decoration. {@code @PreAuthorize}
     * checks that the CALLER owns {@code salonId}; without this check an owner could pass another
     * salon's {@code serviceId} and edit its prices, because the annotation never looks at the
     * second id. <b>Authorising the path and then trusting the body is the standard shape of this
     * bug.</b> A mismatch is a 404, not a 403 — it shouldn't confirm the id exists.
     */
    private com.bmp.salon.entities.SalonService requireServiceOfSalon(UUID salonId, UUID serviceId) {
        com.bmp.salon.entities.SalonService svc = services.findById(serviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND"));
        if (!svc.getSalonId().equals(salonId)) {
            log.warn("Salon {} tried to touch service {}, which belongs to salon {}",
                    salonId, serviceId, svc.getSalonId());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND");
        }
        return svc;
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
                s.getBookingNotifyEmail(), s.getBookingNotifyPhone(),
                // Session 48 — everything the owner typed at signup, given back to them.
                s.getReference(), s.getArea(), s.getPincode(), s.getAddress(), s.getAbout(),
                s.getImageUrl(), categoriesOf(s.getId()), s.getWentLiveAt());
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
                p.isSalonCanRescheduleDirectly(), p.isRescheduleKeepsOriginalClock(),
                // V022 — the booking window.
                p.getBookingHorizonDays(), p.getMinNoticeMinutes());
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * V013 — validate a pasted image link.
     *
     * <h2>Why this is checked at all</h2>
     * The owner types this by hand, and it is rendered by every customer who opens the salon page.
     * Two things must not get through:
     *
     * <ul>
     *   <li><b>{@code javascript:} and {@code data:} URLs.</b> On the web build this string ends up
     *       in an image source; a permissive field is a stored-XSS vector aimed at customers, filed
     *       by the salon itself. Allowing only http/https closes it at the door rather than relying
     *       on every render site to sanitise.</li>
     *   <li><b>Anything that isn't a URL at all.</b> "see instagram" saved silently becomes a
     *       broken image on the salon's own page — a fault the owner cannot see from the editor
     *       and a customer reads as an abandoned business.</li>
     * </ul>
     *
     * <p>An empty string CLEARS the photo, matching category and description. A malformed value is
     * a 400 rather than a silent null: quietly discarding what someone typed is how a field earns
     * a reputation for "not working".
     */
    private static String requireHttpUrlOrNull(String raw) {
        String url = trimToNull(raw);
        if (url == null) return null;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "IMAGE_URL_MUST_BE_HTTP: paste a link that starts with https://");
        }
        try {
            java.net.URI.create(url).toURL();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "IMAGE_URL_INVALID: that doesn't look like a working link");
        }
        return url;
    }

    private ServiceResponse toServiceResponse(com.bmp.salon.entities.SalonService s) {
        return new ServiceResponse(s.getId(), s.getSalonId(), s.getName(), s.getPricePaise().paise(),
                s.getDurationMinutes(), s.isRequiresStylistAssignment(),
                s.getCategory(),      // V011
                s.getArchivedAt(),    // V012
                s.getDescription(),   // V013
                s.getImageUrl());     // V013
    }
}
