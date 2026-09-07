package com.bmp.booking.services;

import com.bmp.booking.api.BookingStatus;
import com.bmp.booking.client.PaymentServiceClient;
import com.bmp.booking.client.RewardsServiceClient;
import com.bmp.booking.client.SalonAvailabilityClient;
import com.bmp.booking.client.dto.AvailabilitySlot;
import com.bmp.booking.dto.BookingDtos.*;
import com.bmp.booking.entities.Booking;
import com.bmp.booking.entities.BookingEvents;
import com.bmp.booking.entities.BookingServiceItem;
import com.bmp.booking.repositories.BookingEventsRepository;
import com.bmp.booking.repositories.BookingRepository;
import com.bmp.booking.repositories.BookingServiceItemRepository;
import com.bmp.common.money.Money;
import com.bmp.common.time.BmpTimeZone;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BMP-25: booking + booking_service_item CRUD, plus append-only booking_events.
 * Real Razorpay-webhook-driven PENDING->CONFIRMED transition is Phase 3 — this ticket
 * intentionally leaves bookings in PENDING and exposes only the CANCEL transition.
 *
 * <p>Session 10: {@link #create} now validates every item's requested slot against
 * bmp-salon-service's availability algorithm (see docs/AVAILABILITY_ALGORITHM.md) before
 * persisting anything — a customer who viewed slots a minute ago and someone else grabbed
 * the same one in the meantime gets a clear 409, not a silent double-booking. Cancelling
 * needs no equivalent code here: the availability algorithm's busy-window query already
 * excludes CANCELLED bookings via its join to this table's status column (see
 * BookingServiceItemRepository.findBusyItemsForStylist), so a cancelled booking frees its
 * slot automatically the moment {@link #cancel} flips the status.
 */
@Service
public class BookingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final BookingServiceItemRepository items;
    private final BookingEventsRepository events;
    private final SalonAvailabilityClient availability;
    /** Session 50 — opening the payment order that lets a booking ever become CONFIRMED. */
    private final com.bmp.booking.client.PaymentServiceClient payments;
    private final com.bmp.booking.client.RewardsServiceClient rewards;
    private final com.bmp.booking.client.UserServiceClient users;
    private final com.bmp.common.outbox.OutboxPublisher outbox;
    /** Session 37 — the reschedule trail. Had no repository at all before this session. */
    private final com.bmp.booking.repositories.BookingModificationRepository modifications;
    /**
     * Session 52 — a counter booking raises its bill immediately. Online bookings get theirs when
     * payment is captured; a walk-in has no capture coming, and the person is at the desk wanting
     * something to pay against.
     */
    private final InvoiceService invoices;
    private final ObjectMapper mapper = new ObjectMapper();


    public BookingService(BookingRepository bookings, BookingServiceItemRepository items,
                           BookingEventsRepository events, SalonAvailabilityClient availability,
                           com.bmp.booking.client.RewardsServiceClient rewards,
                           com.bmp.booking.client.UserServiceClient users,
                           com.bmp.common.outbox.OutboxPublisher outbox,
                           com.bmp.booking.repositories.BookingModificationRepository modifications,
                           com.bmp.booking.client.PaymentServiceClient payments,
                           InvoiceService invoices) {
        this.invoices = invoices;
        this.payments = payments;
        this.modifications = modifications;
        this.bookings = bookings;
        this.items = items;
        this.events = events;
        this.availability = availability;
        this.rewards = rewards;
        this.users = users;
        this.outbox = outbox;
    }

    /**
     * Who this booking is for. Session 52.
     *
     * <h2>Why an identity object rather than two create methods</h2>
     * Counter bookings (V009) need the same twelve things an online booking needs: the salon's
     * price list, the salon's commission, the frozen policy snapshot, slot validation against the
     * availability algorithm, the outbox event, the invoice. A second {@code createCounter} that
     * reimplemented that would be two answers to "is this slot free?" and "what does this cost?",
     * and the one that quietly wins is whichever runs second.
     *
     * <p>So there is ONE creation path, and this record is the only thing that differs.
     *
     * @param customerId      a BMP account. Null for counter bookings.
     * @param salonCustomerId the salon's own contact record (V026). Null for online bookings.
     *                        Exactly one of these two is set — enforced by chk_booking_identity.
     * @param name            for counter bookings this is the ONLY record of who came; for online
     *                        ones it is resolved from bmp-user and this stays null.
     */
    private record BookingIdentity(
            UUID customerId, UUID salonCustomerId, String source,
            String name, String phone, String email, UUID takenByStaffId) {

        static BookingIdentity online(UUID customerId) {
            return new BookingIdentity(customerId, null, "online", null, null, null, null);
        }

        static BookingIdentity counter(UUID salonCustomerId, String name, String phone,
                                        String email, UUID takenByStaffId) {
            return new BookingIdentity(null, salonCustomerId, "counter", name, phone, email,
                    takenByStaffId);
        }

        boolean isCounter() { return "counter".equals(source); }
    }

    @Transactional
    public BookingResponse create(CreateBookingRequest req) {
        return createInternal(req.salonId(), req.items(), req.couponCode(),
                BookingIdentity.online(req.customerId()));
    }

    /**
     * A booking taken at the counter or over the phone. Session 52.
     *
     * <h2>Darshan's requirement, and why the customer record is mandatory</h2>
     * <i>"Suppose any customer calls the manager or comes to walk in, then the manager should
     * update it in the portal and the manager should select the stylist… we should compulsorily
     * have their data in our database. Remember, it's their own customer."</i>
     *
     * <p>So {@code salonCustomerId} is required, not optional. The caller (bmp-salon's counter
     * endpoint) creates or matches the {@code salon_customer} row first and passes its id — which
     * means it is impossible to take a counter booking without recording who it was for. That is
     * the whole point: walk-in trade used to leave a {@code walk_in_block} and nothing else.
     *
     * <h2>CONFIRMED immediately, not PENDING</h2>
     * An online booking waits for a payment webhook to confirm it. A counter booking has no
     * online payment — the person is standing there and will settle at the desk — so leaving it
     * PENDING would mean every walk-in sat unconfirmed forever, exactly the bug that left every
     * booking PENDING before Session 50. It is confirmed on creation, and the invoice records
     * what is owed.
     */
    @Transactional
    public BookingResponse createCounter(UUID salonId, List<ItemRequest> items,
                                          UUID salonCustomerId, String name, String phone,
                                          String email, UUID takenByStaffId) {
        if (salonCustomerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A counter booking must be attached to a customer record.");
        }
        // No coupon on the counter path: coupons belong to a BMP account's redemption history,
        // and there is no account here to charge a use against.
        return createInternal(salonId, items, null,
                BookingIdentity.counter(salonCustomerId, name, phone, email, takenByStaffId));
    }

    /**
     * IS THIS SALON ALLOWED TO TAKE BOOKINGS AT ALL? Session 65.
     *
     * <h2>The hole this closes</h2>
     * Suspending a salon removed it from DISCOVERY — {@code SalonService.PUBLICLY_VISIBLE} is
     * {@code ['active']}, so search hides it and its public page 404s. Nothing checked the status
     * on the way IN to a booking, so the freeze was only as strong as the customer's inability to
     * find the salon:
     *
     * <ul>
     *   <li>a customer with the salon page already open, or a saved deep link, could still book;</li>
     *   <li>the salon's OWN counter desk kept taking walk-ins, because {@code createCounter} runs
     *       inside the salon app and never consulted the platform's opinion of the salon.</li>
     * </ul>
     *
     * That second one is the serious one. Suspension is how BMP stops a business trading — usually
     * because of a complaint that has not been resolved — and the salon carried on taking money for
     * appointments that BMP would then be on the hook for.
     *
     * <h2>Why here, and not at each caller</h2>
     * {@code create} and {@code createCounter} both funnel through {@code createInternal}, so this
     * is the one place both paths pass. A check at each entry point would be two copies, and the
     * third entry point somebody adds next year would have none.
     *
     * <h2>Fails CLOSED, and that costs nothing extra</h2>
     * If bmp-salon is unreachable the booking is refused. That reads harsh for a revenue path until
     * you notice createInternal already calls listServices and getPolicy on the same service a few
     * lines later — bmp-salon being down already means no booking. Refusing here just does it
     * earlier, with a message about the salon rather than a stack trace about a service menu.
     */
    private void requireSalonBookable(UUID salonId) {
        SalonAvailabilityClient.SalonSummary salon;
        try {
            salon = availability.getSalon(salonId);
        } catch (Exception e) {
            log.warn("Could not read salon {} before booking — refusing rather than guessing: {}",
                    salonId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We can't reach the salon's details right now. Please try again in a moment.");
        }
        if (salon == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND");
        }

        /*
         * 'active' is the ONLY bookable status — the same single-element list discovery filters on
         * (SalonService.PUBLICLY_VISIBLE). Deliberately an allow-list rather than "not suspended":
         * a status added later is un-bookable until somebody says otherwise, which is a complaint,
         * rather than bookable by default, which is a liability.
         */
        if (!"active".equalsIgnoreCase(salon.status())) {
            log.warn("Refused a booking for salon {} — status is {}", salonId, salon.status());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "suspended".equalsIgnoreCase(salon.status())
                        ? "This salon is suspended and can't take bookings. Please contact support."
                        : "This salon isn't taking bookings at the moment.");
        }
    }

    private BookingResponse createInternal(UUID salonId, List<ItemRequest> reqItems,
                                            String couponCode, BookingIdentity who) {
        // FIRST, before a reference is minted or anything is written. A suspended salon must not
        // consume a booking number, and a refusal after side effects is a refusal with litter.
        requireSalonBookable(salonId);

        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        String prefix = "BMP-" + year + "-";
        // NOTE: same count-based simplification as support_ticket's ticketRef (BMP-29) —
        // replace with a real Postgres sequence before go-live, not safe under concurrency.
        long seq = bookings.countByBookingRefStartingWith(prefix) + 1;
        String bookingRef = prefix + String.format("%05d", seq);

        /*
         * ═══════════════════════════════════════════════════════════════════════════════════
         * SESSION 30 — THE PRICE COMES FROM THE SALON, NOT FROM THE REQUEST.
         * ═══════════════════════════════════════════════════════════════════════════════════
         * `ItemRequest` carries nameSnapshot, pricePaise and durationMinutes, and this method
         * used to write them straight to the database. They arrive from the CLIENT.
         *
         * A hand-rolled POST could therefore book a ₹4,500 service for ₹1. The duration was
         * worse: understate a 120-minute service as 15 and the availability check passes
         * (15 minutes really is free), the booking is written at 15, and it then overruns three
         * other customers' appointments. Nobody notices until a Saturday.
         *
         * The rule this restores: FIELDS THE CLIENT CANNOT BE TRUSTED WITH ARE NOT READ FROM
         * THE CLIENT. The request now supplies only *choices* — which service, which stylist,
         * what time. Everything with a value attached is resolved here.
         *
         * One extra call per booking, and the salon's own service list is the only place that
         * knows the answer. `serviceMenu` is keyed by id for the item loop below.
         */
        Map<UUID, SalonAvailabilityClient.SalonService> serviceMenu;
        try {
            serviceMenu = availability.listServices(salonId).stream()
                    .collect(Collectors.toMap(SalonAvailabilityClient.SalonService::id, s -> s));
        } catch (Exception e) {
            // Fail the booking rather than fall back to the client's numbers. A booking written
            // at an unverified price is worse than a booking that didn't happen — the first one
            // the salon has to honour, the second the customer just retries.
            log.error("Could not load the service menu for salon {} — refusing the booking. {}",
                    salonId, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't confirm the price with the salon. Nothing has been booked — please try again.");
        }

        Money total = Money.ZERO;
        for (ItemRequest item : reqItems) {
            SalonAvailabilityClient.SalonService svc = serviceMenu.get(item.serviceId());
            if (svc == null) {
                // Either a stale menu on the client, or someone booking a service that belongs
                // to a different salon. Both are refusals, and 404 says which without leaking
                // whether the id exists elsewhere.
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SERVICE_NOT_AVAILABLE: that service isn't on this salon's menu.");
            }
            total = total.plus(Money.ofPaise(svc.pricePaise()));
        }

        /*
         * ═══════════════════════════════════════════════════════════════════════════════════
         * SESSION 30 — COMMISSION AND THE POLICY SNAPSHOT COME FROM THE SALON.
         * ═══════════════════════════════════════════════════════════════════════════════════
         * This was `total.percentBps(1200)` with a TODO, and `policy_snapshot` was the literal
         * string "{}" — on a column whose own migration comment reads "FROZEN copy of
         * salon_policy, never changes". It froze nothing.
         *
         * Why the snapshot matters more than the commission: the cancellation terms a customer
         * agreed to are the ones displayed when they booked. If a salon later switches from
         * 'flexible' to 'strict', every existing booking must keep the old terms — otherwise
         * someone who booked under "free cancellation up to 2 hours before" gets charged under
         * a rule that did not exist when they agreed. Re-deriving terms at cancellation time is
         * how that happens, and an empty snapshot forces exactly that.
         *
         * A salon with no policy row is NORMAL — every salon has none until its owner sets one.
         * That case falls back to the platform default rather than refusing the booking: the
         * customer did nothing wrong, and refusing would make an unconfigured salon unbookable
         * without telling anyone why. The fallback is recorded IN the snapshot
         * (`"source": "platform_default"`), so a later dispute can tell "the salon chose these
         * terms" apart from "nobody had chosen any".
         *
         * Contrast the service menu above, which DOES refuse: a missing price is a booking at
         * an unverified amount, and there is no safe default for someone else's money.
         */
        SalonAvailabilityClient.SalonPolicy policy = null;
        try {
            policy = availability.getPolicy(salonId);
        } catch (Exception e) {
            log.warn("No policy for salon {} ({}) — booking under platform defaults, recorded in the snapshot.",
                    salonId, e.toString());
        }

        int commissionBps = policy != null ? policy.commissionBps() : DEFAULT_COMMISSION_BPS;
        Money commission = total.percentBps(commissionBps);
        String policySnapshot = snapshotPolicy(policy, commissionBps);

        // Resolve + validate every item's stylist/slot BEFORE writing anything — a booking
        // with some items validated and others not is worse than rejecting the whole request.
        List<UUID> resolvedStylistIds = new java.util.ArrayList<>();
        for (ItemRequest item : reqItems) {
            // The SALON's duration, not the request's — see resolveAndValidateSlot's javadoc.
            resolvedStylistIds.add(resolveAndValidateSlot(
                    salonId, item, serviceMenu.get(item.serviceId()).durationMinutes()));
        }

        /*
         * Counter bookings are CONFIRMED on creation; online ones wait for the payment webhook.
         * See createCounter's javadoc — a walk-in that stayed PENDING would never confirm, because
         * there is no online payment coming for it.
         */
        boolean counter = who.isCounter();
        Booking booking = new Booking(bookingRef, salonId, who.customerId(),
                counter ? BookingStatus.CONFIRMED : BookingStatus.PENDING,
                total, Money.ZERO, commission, policySnapshot, true,
                counter ? Instant.now() : null);
        booking.setSource(who.source());
        booking.setSalonCustomerId(who.salonCustomerId());
        booking.setTakenByStaffId(who.takenByStaffId());
        booking = bookings.save(booking);

        // Session 22: apply the coupon, if one was given.
        //
        // AFTER the slot validation and the booking insert, deliberately. Redeeming consumes
        // one of the customer's uses, so it must not happen for a booking that then fails on an
        // unavailable slot — they'd lose a single-use coupon to a booking that never existed.
        //
        // Inside the same @Transactional, so a failure anywhere below rolls the redemption back
        // with everything else.
        applyCouponIfPresent(booking, couponCode, who.customerId(), total);

        List<ItemResponse> itemResponses = new java.util.ArrayList<>();
                for (int i = 0; i < reqItems.size(); i++) {
            ItemRequest item = reqItems.get(i);
            UUID assignedStylistId = resolvedStylistIds.get(i);

            // The salon's numbers, not the request's. Present by construction — the loop above
            // already refused any item whose serviceId isn't on this salon's menu.
            SalonAvailabilityClient.SalonService svc = serviceMenu.get(item.serviceId());

            // `end` is derived from the SALON's duration. Taking it from the request is what
            // let a 120-minute appointment be written as 15 and silently overrun the next three.
            Instant end = item.start().plus(Duration.ofMinutes(svc.durationMinutes()));

            BookingServiceItem entity = new BookingServiceItem(booking.getId(), item.serviceId(), assignedStylistId,
                    item.selectionType(), item.start(), end, svc.name(), Money.ofPaise(svc.pricePaise()),
                    svc.durationMinutes(), svc.durationMinutes(), "active");
            entity = items.save(entity);
            itemResponses.add(toItemResponse(entity));
        }

        recordEvent(booking.getId(), "CREATED", who.isCounter() ? "salon" : "customer",
                who.isCounter() ? who.takenByStaffId() : who.customerId(), Map.of());

        /*
         * ═══════════════════════════════════════════════════════════════════════════════════
         * SESSION 34 — TELL SOMEBODY.
         * ═══════════════════════════════════════════════════════════════════════════════════
         * The `recordEvent` line above has been here since Session 3 and does NOT do this. It
         * writes to booking_events, this service's own append-only audit trail. Useful, and
         * entirely local — nothing outside bmp-booking has ever heard about a booking.
         *
         * The consequence: a customer could book an appointment and receive nothing. No
         * confirmation, no reminder, no notice of their own cancellation. The only message BMP
         * had ever sent them was their login OTP. A booking app that doesn't confirm bookings
         * gets uninstalled after the first appointment, and the salon absorbs the no-shows.
         *
         * Two `event` systems with similar names, one local and one cross-service, and the
         * local one was written first. Nothing ever failed, because a missing event is silence
         * rather than an exception. Worth remembering the next time a line LOOKS like it
         * publishes.
         *
         * The contact snapshot happens FIRST and is allowed to fail — see resolveContact.
         */
        SalonAvailabilityClient.SalonSummary salonSummary =
                snapshotContact(booking, who, salonId, bookingRef);

        /*
         * Session 37 — pin the cancellation clock, once, forever.
         *
         * Written here and never touched by reschedule. Without it: book Saturday 11:00,
         * reschedule to next month at 10:00 on the day, cancel "three weeks ahead" — free,
         * having cost the salon Saturday's slot with an hour's notice.
         */
        booking.setOriginalStart(itemResponses.stream()
                .map(ItemResponse::serviceStart)
                .min(Instant::compareTo)
                .orElse(null));

        booking = bookings.save(booking);

        /*
         * ══════════════════════════════════════════════════════════════════════════════════════
         * SESSION 50 — OPEN THE PAYMENT ORDER. This is the link that never existed.
         * ══════════════════════════════════════════════════════════════════════════════════════
         * bmp-booking and bmp-payment were not connected in either direction, which is why every
         * booking BMP has ever taken sits PENDING: BookingStatus reserves PENDING -> CONFIRMED
         * for the "Razorpay webhook ONLY", and no webhook existed to perform it.
         *
         * The commission rate passed here is the SALON'S, read once above for the policy
         * snapshot. Passing it rather than letting bmp-payment fetch its own means the split and
         * the cancellation terms frozen beside it can never come from two different reads of a
         * policy somebody edited in between.
         *
         * NON-FATAL, and that is a real decision. The booking, its items and its slot holds are
         * already committed and correct. If the payment service is down, the customer has a
         * booking they cannot pay for yet — recoverable, and the salon can still take the money
         * at the counter. Rolling the whole booking back would instead lose a validated slot for
         * a reason that has nothing to do with the customer.
         *
         * Logged at ERROR because until this succeeds the booking cannot be confirmed by payment.
         */
        if (counter) {
            /*
             * No online payment order for a counter booking. The customer has no BMP account to
             * pay from and is standing at the desk; the money is taken there and recorded against
             * the invoice. Opening an order nobody can settle would leave a permanent unpaid
             * order per walk-in and make the payments dashboard meaningless.
             */
            log.info("Counter booking {} confirmed at the salon — no online payment order opened.",
                    booking.getBookingRef());
            /*
             * Raise the bill now. Non-fatal: the appointment is real and already committed, and a
             * salon that cannot print a receipt this second can still take the money and reprint
             * later. Losing a validated slot because a document failed would be the wrong trade.
             */
            try {
                invoices.issueFor(booking.getId(),
                        salonSummary == null ? booking.getSalonNameSnapshot() : salonSummary.name(),
                        null);
            } catch (Exception e) {
                log.error("Counter booking {} was created but its invoice could not be raised ({}). "
                        + "The booking stands; the bill can be reissued from the desk.",
                        booking.getBookingRef(), e.toString());
            }
        } else try {
            var order = payments.create(booking.getId(), new PaymentServiceClient.CreateOrder(
                    booking.getFinalAmountPaise().paise(),
                    booking.getSalonId(),
                    commissionBps,
                    booking.getBookingRef()));
            log.info("Payment order {} opened for booking {} ({} paise, gateway order {}).",
                    order.id(), booking.getBookingRef(), order.amountPaise(), order.razorpayOrderId());
        } catch (Exception e) {
            log.error("Booking {} was created but NO PAYMENT ORDER could be opened ({}). The "
                    + "customer cannot pay online and the booking will stay PENDING until "
                    + "somebody records a counter payment against its invoice.",
                    booking.getBookingRef(), e.toString());
        }

        outbox.publish(new com.bmp.common.events.BookingCreated(
                booking.getId(), booking.getBookingRef(), booking.getSalonId(),
                booking.getSalonNameSnapshot(), booking.getCustomerId(), booking.getCustomerName(),
                booking.getCustomerPhone(), booking.getCustomerEmail(),
                // The earliest service start — a booking of a cut at 11:00 and a colour at 11:45
                // is "your appointment is at 11:00", not at 11:45.
                itemResponses.stream().map(ItemResponse::serviceStart)
                        .min(Instant::compareTo).orElse(null),
                itemResponses.stream().map(ItemResponse::nameSnapshot).toList(),
                booking.getFinalAmountPaise().paise(),
                // Session 40 — so the SALON hears about it too. Previously this event reached
                // the customer and nobody else, and a salon only found out by having the desk
                // open (it polls every 60s). A booking made overnight was invisible.
                salonSummary == null ? null : salonSummary.bookingNotifyEmail(),
                salonSummary == null ? null : salonSummary.bookingNotifyPhone()));

        // Session 53 — and tell the people who have to do the work. Never fails the booking.
        notifyAssignedStylists(booking, itemResponses, "booked",
                salonSummary == null ? booking.getSalonNameSnapshot() : salonSummary.name(), null);

        return toResponse(booking, itemResponses);
    }


    /**
     * Tell each assigned stylist their diary changed. Session 53.
     *
     * <h2>The silence this ends</h2>
     * The customer was told at booking (Session 34) and the salon was told (Session 40). The
     * stylist — the person who actually has to be at the chair — was told nothing, and found out
     * by opening the app. Worst for a counter booking a manager takes ten minutes beforehand.
     *
     * <h2>What it must not carry</h2>
     * No customer name, phone or id, and no price. Enforced by the SHAPE of
     * {@link StylistAppointmentChanged}, which has no field for any of them, so no template
     * written later can leak one. Same rule as {@code ScheduleEntryResponse} (Session 48).
     *
     * <h2>Never fails the booking</h2>
     * Every lookup is wrapped. A stylist who is not told still has the appointment on their
     * schedule, and failing a real booking because a name lookup timed out would be a far worse
     * trade than a missing email. Logged at WARN with the booking ref so it can be found.
     *
     * <p>One event per stylist: a cut with Anjali at 11:00 and a colour with Imran at 11:45 is one
     * booking and two diaries, and the message differs for each.
     *
     * @param change {@code booked} | {@code moved} | {@code cancelled}
     * @param previousStarts per stylist, only for {@code moved}; null or absent otherwise
     */
    private void notifyAssignedStylists(Booking booking, List<ItemResponse> itemResponses,
                                          String change, String salonName,
                                          Map<UUID, Instant> previousStarts) {
        // Group this booking's items by the person doing them, so somebody with two services in
        // one booking gets ONE message covering both rather than two that each look complete.
        Map<UUID, List<ItemResponse>> byStylist = new java.util.LinkedHashMap<>();
        for (ItemResponse item : itemResponses) {
            if (item.assignedStylistId() == null) continue; // unassigned: nobody to tell yet
            byStylist.computeIfAbsent(item.assignedStylistId(), k -> new java.util.ArrayList<>())
                    .add(item);
        }

        for (var entry : byStylist.entrySet()) {
            UUID stylistId = entry.getKey();
            List<ItemResponse> mine = entry.getValue();
            try {
                var contact = availability.stylistContact(stylistId);
                String email = null;
                String name = contact == null ? null : contact.name();

                if (contact != null && contact.userId() != null) {
                    // An invite-created profile nobody claimed has no account and no address.
                    var body = users.getUserById(contact.userId()).getBody();
                    if (body != null) {
                        email = body.email();
                        if (name == null || name.isBlank()) name = body.name();
                    }
                }

                Instant startsAt = mine.stream().map(ItemResponse::serviceStart)
                        .min(Instant::compareTo).orElse(null);
                Instant endsAt = mine.stream().map(ItemResponse::serviceEnd)
                        .max(Instant::compareTo).orElse(null);
                int minutes = startsAt == null || endsAt == null
                        ? 0
                        : (int) java.time.Duration.between(startsAt, endsAt).toMinutes();

                outbox.publish(new com.bmp.common.events.StylistAppointmentChanged(
                        booking.getId(), booking.getBookingRef(), stylistId, booking.getSalonId(),
                        salonName, change, startsAt,
                        previousStarts == null ? null : previousStarts.get(stylistId),
                        minutes,
                        mine.stream().map(ItemResponse::nameSnapshot).toList(),
                        email, name));
            } catch (Exception e) {
                log.warn("Booking {} was {} but stylist {} could not be notified ({}). The "
                        + "appointment is on their schedule either way.",
                        booking.getBookingRef(), change, stylistId, e.toString());
            }
        }
    }

    /**
     * Resolves the customer's contact details and the salon's name, and writes them onto the
     * booking. Session 34.
     *
     * <h2>Neither failure is allowed to fail the booking</h2>
     * This is the deliberate opposite of the {@code serviceMenu} call at the top of
     * {@link #create}, and the distinction is the point:
     *
     * <ul>
     *   <li><b>Price</b> is part of the agreement and cannot be guessed. An unreachable bmp-salon
     *       must refuse the booking, because a booking written at an unverified price is one the
     *       salon then has to honour.</li>
     *   <li><b>Contact details</b> are not part of the agreement. A customer whose appointment
     *       was accepted but whose confirmation never sent still has an appointment — it's in
     *       the app. Refusing the booking to protect the receipt would be backwards.</li>
     * </ul>
     *
     * <p>So both calls catch broadly, log at WARN with the booking ref (so the specific booking
     * can be found and repaired), and leave nulls behind.
     * {@code NotificationDispatcher} then says out loud that it had nowhere to send.
     *
     * <h2>Why store it rather than look it up when needed</h2>
     * {@link #cancel} and {@link #salonTransition} publish events too. If they resolved contact
     * live, a customer could not cancel their appointment while bmp-user was restarting — and
     * being unable to cancel is a far worse outcome than a stale phone number. Snapshotting here
     * means those paths make no outbound call at all.
     *
     * <p>The accepted cost: a customer who changes their number after booking is messaged at the
     * old one. If that ever bites, refresh the snapshot on the reminder job — do NOT make the
     * booking path depend on a live lookup.
     */
    /**
     * Session 40: also returns the salon's booking-alert contact, so {@code create} can put it on
     * the event. Null when bmp-salon was unreachable or the salon hasn't set one — the booking
     * still succeeds either way, and the dispatcher reports that nobody was told.
     */
    private SalonAvailabilityClient.SalonSummary snapshotContact(
            Booking booking, BookingIdentity who, UUID salonId, String bookingRef) {
        SalonAvailabilityClient.SalonSummary salonSummary = null;

        /*
         * Session 52 — a counter booking's contact details come from the person at the desk, not
         * from bmp-user. There is no account to look up, and calling bmp-user with a null id would
         * be a guaranteed 404 on every walk-in.
         *
         * These snapshot columns are the ONLY record of who this booking is for, which is why
         * chk_counter_has_contact (V009) makes them mandatory for counter rows.
         */
        if (who.isCounter()) {
            booking.setCustomerName(who.name());
            booking.setCustomerPhone(who.phone());
            booking.setCustomerEmail(who.email());
            try {
                var salon = availability.getSalon(salonId);
                if (salon != null) {
                    booking.setSalonNameSnapshot(salon.name());
                    salonSummary = salon;
                }
            } catch (Exception e) {
                log.warn("Could not resolve the name of salon {} for counter booking {} ({}).",
                        salonId, bookingRef, e.toString());
            }
            return salonSummary;
        }

        UUID customerId = who.customerId();
        try {
            var body = users.getUserById(customerId).getBody();
            if (body != null) {
                booking.setCustomerName(body.name());
                booking.setCustomerPhone(body.phone());
                booking.setCustomerEmail(body.email());
            } else {
                // A 2xx with an empty body. Shouldn't happen, but "shouldn't" is not "doesn't",
                // and a silent null here would look identical to a customer with no details.
                log.warn("bmp-user returned an empty body for customer {} on booking {} — the "
                        + "booking stands, but we have no way to contact them about it.",
                        customerId, bookingRef);
            }
        } catch (Exception e) {
            log.warn("Could not resolve contact details for customer {} on booking {} ({}). "
                    + "The booking is fine; the confirmation will not be sent. Repair by hand "
                    + "if the customer asks why they heard nothing.", customerId, bookingRef, e.toString());
        }

        try {
            var salon = availability.getSalon(salonId);
            if (salon != null) {
                booking.setSalonNameSnapshot(salon.name());
                salonSummary = salon;
            }
        } catch (Exception e) {
            // Note this call succeeded moments ago for the service menu, so reaching here means
            // bmp-salon fell over mid-booking. The message degrades to "your booking" without a
            // shop name, which is worse but still true.
            log.warn("Could not resolve the name of salon {} for booking {} ({}). The "
                    + "confirmation will not name the salon, and the SALON will not be alerted.",
                    salonId, bookingRef, e.toString());
        }
        return salonSummary;
    }

    /**
     * Applies a coupon code, if the request carried one.
     *
     * <p><b>bmp-booking never decides what a coupon is worth.</b> It sends the code and the
     * basket; bmp-rewards answers with a discount and a commission basis. Duplicating the
     * discount rules here would mean two implementations that eventually disagree, and the one
     * that quietly wins is whichever runs last.
     *
     * <p><b>A bad code fails the booking.</b> Not silently ignored, not applied at zero. The
     * customer chose to book at a discounted price they were shown; charging them full price
     * because their code turned out to be exhausted is a worse outcome than a failed booking
     * they can retry — and it's the kind of thing they only notice on their card statement.
     *
     * <p>{@code isFirstBooking} is computed here rather than asked of bmp-rewards, because
     * bmp-booking owns that fact. It excludes this booking (which was just inserted) by
     * comparing against 1, not 0.
     */
    private void applyCouponIfPresent(Booking booking, String code, UUID customerId, Money total) {
        if (code == null || code.isBlank()) {
            return;
        }
        // Session 52: a counter booking has no BMP account, so there is nothing to redeem a
        // coupon against and no redemption history to charge a use to. createCounter passes null,
        // so this is defence rather than a live path — but it fails loudly rather than silently
        // giving away a discount that no allowance is decremented for.
        if (customerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Coupons need a BMP account. Take the discount at the counter instead.");
        }

        // "First booking" means this is their only one — the row we just wrote.
        boolean isFirstBooking = bookings.countByCustomerId(customerId) <= 1;

        RewardsServiceClient.RedeemResponse redeemed;
        try {
            redeemed = rewards.redeem(new RewardsServiceClient.RedeemRequest(
                    code.trim(), customerId, booking.getSalonId(), booking.getId(),
                    total.paise(), isFirstBooking));
        } catch (feign.FeignException e) {
            // Feign surfaces a remote error as FeignException, NOT as the ResponseStatusException
            // the other side threw — so the two cases have to be told apart by status code.
            // Getting this wrong means an exhausted coupon is reported as "try again later",
            // and the customer retries forever.
            if (e.status() == HttpStatus.CONFLICT.value() || e.status() == HttpStatus.NOT_FOUND.value()) {
                // bmp-rewards refuses with a message written FOR the customer ("this code needs
                // a minimum spend of ₹1,000"). Pass it through — the specific version is the
                // one they can act on.
                log.info("Coupon {} refused for booking {}: {}", code, booking.getId(), e.contentUTF8());
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "COUPON_NOT_APPLIED: " + extractMessage(e.contentUTF8()));
            }
            log.error("Coupon redemption failed for booking {} code={} status={} ({})",
                    booking.getId(), code, e.status(), e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't apply that code just now. Please try again in a moment.");
        } catch (Exception e) {
            // bmp-rewards unreachable. Failing the booking is the honest choice: proceeding at
            // full price would charge someone more than the total they just agreed to.
            log.error("Coupon redemption failed for booking {} code={} ({})",
                    booking.getId(), code, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't apply that code just now. Please try again in a moment.");
        }

        Money discount = Money.ofPaise(redeemed.discountPaise());
        Money finalAmount = Money.ofPaise(Math.max(0, total.paise() - redeemed.discountPaise()));

        booking.applyDiscount(redeemed.couponId(), total, discount, finalAmount, redeemed.commissionBase());
        bookings.save(booking);

        recordEvent(booking.getId(), "COUPON_APPLIED", "customer", customerId,
                Map.of("code", redeemed.code(), "discountPaise", redeemed.discountPaise()));

        log.info("Coupon {} applied to booking {}: -{}p (commission base {})",
                redeemed.code(), booking.getId(), redeemed.discountPaise(), redeemed.commissionBase());
    }

    /**
     * Pulls the human-readable half out of a Spring error body.
     *
     * <p>Spring's default error JSON is {@code {"status":409,"error":"Conflict","message":"..."}}.
     * Showing a customer the whole blob would be worse than showing nothing; showing them the
     * message is the entire point of bmp-rewards writing specific refusals.
     *
     * <p>Falls back to the raw body rather than an empty string — a slightly ugly message beats
     * a silent one when something unexpected comes back.
     */
    private String extractMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "That code could not be applied.";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(errorBody);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // Not JSON, or an unexpected shape — fall through.
        }
        return errorBody;
    }

    /**
     * Confirms one item's requested (stylist, start, duration) is actually free right now,
     * per bmp-salon's AvailabilityApi — or, for an "any_available" item with no stylist
     * chosen, picks one from whichever stylist has that exact slot free. Returns the
     * stylist id to assign to the booking_service_item.
     *
     * <p>Deliberately re-checks the FULL slot (not just "is this stylist busy at this
     * instant") by matching against AvailabilityService's own slot list, so the same
     * working-hours/breaks/leave/salon-hours rules apply here as when the customer first
     * saw this slot offered to them — not a separate, looser check.
     *
     * <p><b>Session 30: {@code durationMinutes} is now a parameter, and it must be the SALON's
     * duration — never {@code item.durationMinutes()}.</b> This method used to read the
     * client's value, which made the whole check bypassable: understate a 120-minute service as
     * 15, and bmp-salon is asked "is there a 15-minute gap here?" It says yes, the booking is
     * accepted, and the appointment then runs over the next three. Validating against a length
     * the caller chose is not validation.
     */
    /**
     * The platform's commission when a salon hasn't set its own.
     *
     * <p>Mirrors the DEFAULT on {@code salon_policy.commission_bps} (V009). Duplicated here for
     * the one case the column can't cover — a salon with no policy row at all — and NOT a
     * commercially agreed number; see V009's header and the Track 0 item in
     * {@code docs/PENDING_WORK.md}. If you change one, change both.
     */
    private static final int DEFAULT_COMMISSION_BPS = 1200;

    /**
     * Freeze the salon's terms onto the booking, as JSON.
     *
     * <p>Written once at creation and never updated — that is the entire point. A cancellation
     * three weeks later reads THIS, not today's salon_policy, so a salon tightening its terms
     * cannot retroactively change what an existing customer agreed to.
     *
     * <p>{@code source} distinguishes "the salon chose these" from "the salon had chosen
     * nothing, so the platform's defaults applied". In a dispute those are different facts, and
     * a snapshot that hides the difference is worth much less than one that records it.
     */
    private String snapshotPolicy(SalonAvailabilityClient.SalonPolicy policy, int commissionBps) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("source", policy != null ? "salon_policy" : "platform_default");
        snapshot.put("capturedAt", Instant.now().toString());
        snapshot.put("commissionBps", commissionBps);
        snapshot.put("template", policy != null ? policy.template() : "standard");
        snapshot.put("freeCancelHours", policy != null ? policy.freeCancelHours() : 24);
        snapshot.put("lateGraceMinutes", policy != null ? policy.lateGraceMinutes() : 15);
        snapshot.put("requirePrepayment", policy != null && policy.requirePrepayment());

        /*
         * Session 37 — the terms that decide what a cancellation costs and whether the booking
         * can still be moved.
         *
         * Frozen here for the same reason as everything else in this map: a salon that tightens
         * its cancellation window on Tuesday must not retroactively change what a customer
         * agreed to on Monday. CancellationTerms reads ONLY from this snapshot and never calls
         * bmp-salon — one live lookup in that class would quietly undo all of this, and nobody
         * would notice until a charge was disputed.
         *
         * Every fee falls back to 0 when there is no salon policy. If we cannot say what the
         * customer agreed to, we cannot claim they agreed to pay.
         */
        snapshot.put("lateCancelHours", policy != null ? policy.lateCancelHours() : 2);
        snapshot.put("lateCancelFeeBps", policy != null ? policy.lateCancelFeeBps() : 0);
        snapshot.put("noNoticeFeeBps", policy != null ? policy.noNoticeFeeBps() : 0);
        snapshot.put("rescheduleNoticeHours", policy != null ? policy.rescheduleNoticeHours() : 24);
        snapshot.put("maxReschedulesPerBooking", policy != null ? policy.maxReschedulesPerBooking() : 2);
        snapshot.put("salonCanRescheduleDirectly", policy != null && policy.salonCanRescheduleDirectly());
        // Defaults to TRUE with no policy — the loophole stays closed unless a salon opens it.
        snapshot.put("rescheduleKeepsOriginalClock",
                policy == null || policy.rescheduleKeepsOriginalClock());

        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // Cannot realistically fail on a map of primitives, but a booking must never be
            // lost to a serialisation problem — and an empty snapshot is exactly the bug this
            // method exists to fix, so say so loudly rather than writing "{}" in silence.
            log.error("Could not serialise the policy snapshot — writing an empty one. "
                    + "This booking's cancellation terms are NOT frozen. {}", e.toString());
            return "{}";
        }
    }

    private UUID resolveAndValidateSlot(UUID salonId, ItemRequest item, int durationMinutes) {
        return resolveAndValidateSlot(salonId, item, durationMinutes, null);
    }

    /**
     * @param excludeBookingId Session 37. Null when creating. Set when RESCHEDULING, so the
     *                         booking being moved doesn't count as busy against itself — without
     *                         it, every short move is refused with "that slot is taken", naming
     *                         the slot the customer already holds.
     */
    private UUID resolveAndValidateSlot(UUID salonId, ItemRequest item, int durationMinutes,
                                         UUID excludeBookingId) {
        LocalDate date = item.start().atZone(BmpTimeZone.ZONE).toLocalDate();
        LocalTime requestedStart = item.start().atZone(BmpTimeZone.ZONE).toLocalTime();

        if (item.stylistId() != null) {
            List<AvailabilitySlot> slots = availability.freeSlots(salonId, item.stylistId(), date, durationMinutes, excludeBookingId);
            boolean stillFree = slots.stream().anyMatch(s -> s.start().equals(requestedStart));
            if (!stillFree) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "SLOT_NOT_AVAILABLE: requested stylist/time is no longer free — someone else may have booked it first");
            }
            return item.stylistId();
        }

        // any_available: no stylist attached to the request — find any stylist who still
        // has this exact start time free and assign them.
        List<AvailabilitySlot> anySlots = availability.freeSlotsAnyStylist(salonId, date, durationMinutes, excludeBookingId);
        return anySlots.stream()
                .filter(s -> s.start().equals(requestedStart))
                .map(AvailabilitySlot::stylistId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "SLOT_NOT_AVAILABLE: no stylist is free for this time slot anymore"));
    }

    public BookingResponse getById(UUID id) {
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        List<ItemResponse> itemResponses = items.findByBookingId(id).stream().map(this::toItemResponse).toList();
        return toResponse(b, itemResponses);
    }

    public PagedBookings list(UUID customerId, String status, int page, int size) {
        Page<Booking> p = status != null
                ? bookings.findByCustomerIdAndStatus(customerId, BookingStatus.valueOf(status), PageRequest.of(page, size))
                : bookings.findByCustomerId(customerId, PageRequest.of(page, size));
        List<BookingResponse> content = p.getContent().stream()
                .map(b -> toResponse(b, items.findByBookingId(b.getId()).stream().map(this::toItemResponse).toList()))
                .toList();
        return new PagedBookings(content, page, size, p.getTotalElements());
    }

    /**
     * Cancel a booking — customer or salon. Session 37 made the actor explicit.
     *
     * <h2>What changed, and why it matters</h2>
     * Until now every cancellation was identical: no fee, no record, no difference between two
     * minutes' notice and three weeks'. {@code freeCancelHours} had been frozen onto every
     * booking since Session 30 and read by nothing. Freezing terms nobody reads is worse than
     * not freezing them, because it looks solved.
     *
     * <h2>The fee is WRITTEN, not computed on read</h2>
     * It is what the customer was told: {@link #previewCancellation} runs the same calculation
     * before they confirm, so the preview and the charge cannot disagree. A dispute is then
     * settled by reading a row, rather than re-running a calculation against a policy that may
     * since have changed — and against a "now" that has moved.
     *
     * <h2>No money is taken here</h2>
     * There are no payments yet (B2/Razorpay). This records the DECISION — which band applied,
     * what it works out to, why. When bmp-payment exists it reads these columns rather than
     * re-deriving them, because the terms that count are the ones frozen at booking time.
     *
     * @param bySalon salon-caused cancellations are always fee-free, and require a reason
     */
    @Transactional
    public BookingResponse cancel(UUID id, CancelRequest req, boolean bySalon, UUID actorUserId) {
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        BookingStatus.Actor actor = bySalon ? BookingStatus.Actor.SALON : BookingStatus.Actor.CUSTOMER;
        try {
            // PENDING and CONFIRMED are both cancellable by both actors — see BookingStatus.
            b.getStatus().assertTransition(BookingStatus.CANCELLED, actor);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        /*
         * A salon must say why, and the customer is shown it.
         *
         * "Your appointment is cancelled" from the business you booked with, with no reason, is
         * the message that loses a customer for good. It also protects the salon: "our stylist
         * is unwell" reads very differently from silence, and it is the difference between a
         * rebooking and a one-star review.
         */
        if (bySalon && (req == null || req.reason() == null || req.reason().trim().length() < 5)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "REASON_REQUIRED: tell the customer why you're cancelling — they will see it.");
        }

        CancellationTerms.Decision decision = CancellationTerms.forCancellation(
                b.getPolicySnapshot(), mapper, effectiveOriginalStart(b), earliestStart(b.getId()),
                b.getFinalAmountPaise().paise(), bySalon, b.getBookingRef());

        b.setStatus(BookingStatus.CANCELLED);
        b.setCancellationFeeBps(decision.feeBps());
        b.setCancellationFeePaise(decision.feePaise());
        b.setCancellationFeeReason(decision.reason());
        b.touch();

        // Session 22: give the coupon use back.
        //
        // The customer shouldn't lose a one-per-person coupon because they cancelled a booking
        // nobody was ever paid for. Best-effort: a failure here must not block the cancellation
        // itself — being unable to cancel is a far worse outcome than a coupon that needs
        // restoring by hand, and the log says exactly which one.
        if (b.getCouponId() != null) {
            try {
                rewards.release(id, req.reason() == null ? "booking cancelled" : req.reason());
            } catch (Exception e) {
                log.error("Booking {} cancelled but its coupon use could not be released ({}). "
                        + "The customer has lost a use of coupon {} — restore it manually.",
                        id, e.toString(), b.getCouponId());
            }
        }

        Map<String, Object> cancelMeta = new java.util.LinkedHashMap<>();
        if (req != null && req.reason() != null) {
            cancelMeta.put("reason", req.reason());
        }
        // The decision goes on the append-only trail, which the CUSTOMER can read. If a fee is
        // ever disputed, the customer has the same record support does.
        cancelMeta.put("feeReason", decision.reason());
        cancelMeta.put("feeBps", decision.feeBps());
        cancelMeta.put("feePaise", decision.feePaise());
        cancelMeta.put("hoursNotice", decision.hoursUntil());
        recordEvent(id, "CANCELLED", bySalon ? "salon" : "customer",
                bySalon ? actorUserId : b.getCustomerId(), cancelMeta);

        List<ItemResponse> itemResponses = items.findByBookingId(id).stream().map(this::toItemResponse).toList();

        /*
         * Session 34 — the cancellation receipt.
         *
         * Zero outbound calls: every contact detail was snapshotted at booking time (V006).
         * That is the whole reason for the snapshot — a customer must be able to cancel while
         * bmp-user is restarting.
         *
         * Why message someone who just pressed Cancel themselves: it is the RECEIPT. "You
         * cancelled BMP-2026-00042 for Saturday 11:00" is what a customer points at when a salon
         * later says they never cancelled. Without it, the only record lives in BMP's database,
         * which is exactly the record a disputing party won't accept.
         *
         * Nothing here mentions a refund. What comes back depends on policy_snapshot and on a
         * payment that doesn't exist yet — and a confident refund figure that turns out to be
         * wrong, in writing, is worse than no figure. That message belongs to bmp-payment.
         *
         * Session 37: `cancelledBy` is now real. A salon-initiated cancellation is not a receipt
         * — it is news the customer has not heard yet, and the dispatcher words it differently.
         */
        outbox.publish(new com.bmp.common.events.BookingCancelled(
                b.getId(), b.getBookingRef(), b.getSalonNameSnapshot(), b.getCustomerId(),
                b.getCustomerName(), b.getCustomerPhone(), b.getCustomerEmail(),
                itemResponses.stream().map(ItemResponse::serviceStart).min(Instant::compareTo).orElse(null),
                req == null ? null : req.reason(), bySalon ? "salon" : "customer"));

        // Session 53 — the stylist's afternoon just freed up. Worth knowing, and until now the
        // only way to find out was to notice an empty chair.
        notifyAssignedStylists(b, itemResponses, "cancelled", b.getSalonNameSnapshot(), null);

        return toResponse(b, itemResponses);
    }

    /**
     * What cancelling would cost, WITHOUT cancelling. Session 37.
     *
     * <p>Runs the identical {@link CancellationTerms#forCancellation} call the real cancellation
     * makes, so the number shown before the customer confirms and the number written afterwards
     * cannot disagree. Two implementations of "what does this cost" is two answers, and the one
     * that quietly wins is whichever runs second.
     *
     * <p>Read-only and free to call. The UI asks on every open of the cancel confirmation,
     * because a fee that changes while the sheet is open (the appointment crossing a band
     * boundary) should be shown as it is now, not as it was when the screen loaded.
     */
    public CancelPreviewResponse previewCancellation(UUID id) {
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        CancellationTerms.Decision d = CancellationTerms.forCancellation(
                b.getPolicySnapshot(), mapper, effectiveOriginalStart(b), earliestStart(b.getId()),
                b.getFinalAmountPaise().paise(), false, b.getBookingRef());
        return new CancelPreviewResponse(d.feeBps(), d.feePaise(),
                Math.max(0, b.getFinalAmountPaise().paise() - d.feePaise()),
                d.reason(), d.hoursUntil(), explainFee(d));
    }

    /**
     * The fee, in words a customer can act on.
     *
     * <p>Composed on the SERVER, not in the app. Three clients would otherwise each write their
     * own version of the salon's terms, and the one a customer screenshots in a dispute would be
     * whichever was least carefully worded.
     */
    private String explainFee(CancellationTerms.Decision d) {
        return switch (d.reason()) {
            case CancellationTerms.FREE ->
                    "You're cancelling in good time, so there's nothing to pay.";
            case CancellationTerms.NO_POLICY ->
                    "This salon hasn't set cancellation terms, so there's nothing to pay.";
            case CancellationTerms.SALON_CANCELLED ->
                    "The salon cancelled this booking, so there's nothing to pay.";
            case CancellationTerms.LATE, CancellationTerms.NO_NOTICE -> d.feePaise() == 0
                    // A zero fee in a charging band is NOT the same message as a free
                    // cancellation, and saying "in good time" here would be a small lie the
                    // salon would have to live with.
                    ? "This is a late cancellation, but this salon doesn't charge for it."
                    : "This is a late cancellation. This salon's terms mean a "
                      + (d.feeBps() / 100) + "% charge applies.";
            default -> "";
        };
    }

    /**
     * The clock anchor: where the appointment was FIRST booked for.
     *
     * <p>Falls back to the earliest current item for pre-V007 bookings. Correct for every one of
     * them by construction — none can have been rescheduled, because rescheduling did not exist.
     */
    private Instant effectiveOriginalStart(Booking b) {
        return b.getOriginalStart() != null ? b.getOriginalStart() : earliestStart(b.getId());
    }

    /** Where the appointment sits now. */
    private Instant earliestStart(UUID bookingId) {
        return items.findByBookingId(bookingId).stream()
                .map(BookingServiceItem::getServiceStart)
                .min(Instant::compareTo)
                .orElse(null);
    }

    // ======================================================================================
    // Session 16 — the salon side. Everything above this line is customer-facing.
    // ======================================================================================

    /**
     * What's coming up at this salon, soonest first. Session 37.
     *
     * <h2>Why this isn't just the history endpoint with a filter</h2>
     * History orders by {@code created_at} — when the booking was MADE. A booking created
     * yesterday for next month sorts above one created last week for tomorrow. Correct for
     * "what did we take recently", useless for "who's coming in". The ordering has to be by the
     * appointment time, which lives on the item.
     *
     * <p>Cancelled, completed and no-show bookings are excluded. The day view keeps cancelled
     * ones so a manager can see a slot freed up on the timeline; a forward list is a work queue,
     * and padding a queue with things that aren't happening is how people stop trusting it.
     *
     * <p>One row per BOOKING, collapsed from the items — a cut plus a colour is one person
     * arriving once. The query returns items because only they carry the time to sort by.
     */
    public PagedBookings salonUpcoming(UUID salonId, int page, int size) {
        // Over-fetch: a booking with three services produces three item rows, so asking for
        // exactly `size` items could yield as few as size/3 bookings. 4x is generous enough for
        // any realistic booking and still bounded.
        List<BookingServiceItem> upcomingItems = items.findUpcomingForSalon(
                salonId, Instant.now(), PageRequest.of(0, (page + 1) * size * 4));

        // LinkedHashSet: the query's ORDER BY is the answer, and a plain distinct() over a
        // HashSet would throw the ordering away — which is the only thing this endpoint adds.
        List<UUID> orderedBookingIds = upcomingItems.stream()
                .map(BookingServiceItem::getBookingId)
                .distinct()
                .toList();

        int from = Math.min(page * size, orderedBookingIds.size());
        int to = Math.min(from + size, orderedBookingIds.size());
        List<UUID> pageIds = orderedBookingIds.subList(from, to);

        Map<UUID, Booking> parents = bookings.findAllById(pageIds).stream()
                .collect(Collectors.toMap(Booking::getId, b -> b));

        List<BookingResponse> content = pageIds.stream()
                .map(parents::get)
                .filter(java.util.Objects::nonNull)
                .map(b -> toResponse(b, items.findByBookingId(b.getId()).stream()
                        .map(this::toItemResponse).toList()))
                .toList();

        // totalElements is what we could SEE, not a true count — the over-fetch is bounded, so a
        // salon with thousands of future bookings will under-report. Honest for the paging
        // controls, and the alternative is a second count query on the hot path of a screen that
        // nobody pages deeply into.
        return new PagedBookings(content, page, size, orderedBookingIds.size());
    }

    /**
     * Can the CUSTOMER still move this booking? Asked before the button is shown.
     *
     * <p>So the app never offers an action that is about to 409, and — more useful — so the
     * refusal is the salon's own terms in words the customer can act on. "This salon needs 24
     * hours' notice to change a booking" tells them to cancel instead, while it is still free.
     * "You can't reschedule" produces a support ticket.
     */
    public RescheduleEligibility rescheduleEligibility(UUID bookingId) {
        Booking b = bookings.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CONFIRMED) {
            return new RescheduleEligibility(false,
                    b.getStatus().isTerminal()
                            ? "This booking is " + b.getStatus().name().toLowerCase(java.util.Locale.ROOT)
                              + " and can't be changed."
                            : "This appointment has already started.",
                    0, b.getRescheduleCount(), 0);
        }

        CancellationTerms.RescheduleCheck c = CancellationTerms.forCustomerReschedule(
                b.getPolicySnapshot(), mapper, earliestStart(bookingId),
                b.getRescheduleCount(), b.getBookingRef());
        return new RescheduleEligibility(c.allowed(), c.refusal(), c.noticeHours(), c.used(), c.max());
    }

    // ======================================================================================
    // Session 37 — rescheduling. Described in BookingStatus's javadoc since Session 3,
    // never built, and described wrongly (it named columns that don't exist on this table).
    // `booking_modification` has sat in the schema since V002 with zero rows ever written.
    // ======================================================================================

    /**
     * Move a booking to a new time.
     *
     * <h2>Status does not change</h2>
     * Reschedule is not a state transition. It moves {@code service_start}/{@code service_end}
     * on the items and appends to {@code booking_modification}. A PENDING booking stays PENDING.
     *
     * <p>Allowed from PENDING as well as CONFIRMED, and that is not a loosening: payments don't
     * exist, so <b>every real booking is PENDING</b>. A reschedule restricted to CONFIRMED would
     * be unreachable code shipped as a feature.
     *
     * <h2>The new slot is validated exactly like a new booking</h2>
     * Same {@code resolveAndValidateSlot}, same salon duration, same availability algorithm. A
     * reschedule that skipped the check would be a supported way to double-book a stylist —
     * which is the one thing this whole system exists to prevent.
     *
     * <p>The order matters: <b>validate every item before writing any of them</b>. A booking
     * with half its services moved is worse than a refused reschedule, because nobody can tell
     * from the outside that it happened.
     *
     * <h2>What is NOT re-derived</h2>
     * Price, duration, commission, the coupon, and {@code policy_snapshot} all stay exactly as
     * they were. The customer is moving an appointment, not rebuying it — re-pricing on a
     * reschedule would let a salon's price rise apply to a booking somebody already agreed to,
     * and would silently re-run the coupon rules months after the code was used.
     *
     * <p>{@code originalStart} is likewise untouched. That is the loophole guard: see V007.
     *
     * @param bySalon a salon move doesn't count against the customer's allowance, and doesn't
     *                need the customer's notice period — but does need a reason
     */
    @Transactional
    public BookingResponse reschedule(UUID bookingId, RescheduleRequest req, boolean bySalon, UUID actorUserId) {
        Booking b = bookings.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        if (b.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "BOOKING_" + b.getStatus().name() + ": this booking can't be moved any more.");
        }
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CONFIRMED) {
            // ARRIVED / IN_SERVICE — they're in the chair. Moving it is not a scheduling
            // operation at that point, it's a conversation.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "BOOKING_IN_PROGRESS: this appointment has already started.");
        }

        /*
         * ── A SUSPENDED SALON CANNOT ACQUIRE NEW FUTURE COMMITMENTS. Session 65, Darshan's call. ─
         *
         * The rule he chose: existing appointments stand and can be CANCELLED; they cannot be
         * moved further into the future at a salon that is not allowed to trade.
         *
         * Without this, suspension leaked through the back door. Blocking creation (see
         * requireSalonBookable) stops new bookings; rescheduling an existing one is how a salon
         * would have kept a customer on its books indefinitely, one move at a time, while
         * suspended.
         *
         * Note what is deliberately NOT blocked: cancel and refund. Winding a salon down means
         * customers must be able to get out, and a suspension that traps people in appointments
         * nobody will honour is worse than no suspension.
         */
        requireSalonBookable(b.getSalonId());

        List<BookingServiceItem> existing = items.findByBookingId(bookingId);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BOOKING_HAS_NO_SERVICES");
        }
        if (req == null || req.items() == null || req.items().size() != existing.size()) {
            // Every service must be given a new time. A partial payload would leave some items
            // at the old time and some at the new, splitting one appointment across two days.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ALL_ITEMS_REQUIRED: send a new time for each of the " + existing.size()
                    + " service(s) on this booking.");
        }

        if (bySalon) {
            requireSalonMayReschedule(b);
            if (req.reason() == null || req.reason().trim().length() < 5) {
                // Same rule as salon cancellation: the customer sees this, and "your appointment
                // moved" with no explanation is how a salon loses someone.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "REASON_REQUIRED: tell the customer why you're moving their appointment.");
            }
        } else {
            CancellationTerms.RescheduleCheck check = CancellationTerms.forCustomerReschedule(
                    b.getPolicySnapshot(), mapper, earliestStart(bookingId),
                    b.getRescheduleCount(), b.getBookingRef());
            if (!check.allowed()) {
                // 409, and the refusal is the salon's own terms in plain words — a customer who
                // is told "changes need 24 hours' notice" can act on it (usually by cancelling
                // while it's still free). "You can't reschedule" just produces a support ticket.
                throw new ResponseStatusException(HttpStatus.CONFLICT, check.refusal());
            }
        }

        // The old times, captured before anything moves — this is `before_snapshot`.
        String before = snapshotItems(existing);

        /*
         * Session 53 — the same capture, for the stylist's "moved from" line.
         *
         * IT HAS TO HAPPEN HERE, not after the write. `existing` holds MANAGED JPA entities and
         * the write pass below mutates them in place (`item.setServiceStart(...)` on objects that
         * came out of this very list, via `byId`). Reading serviceStart afterwards therefore
         * returns the NEW time, and the message would read "moved from 4pm to 4pm" — technically
         * emitted, entirely useless, and the kind of thing nobody notices until a stylist asks why
         * the email says nothing changed.
         */
        Map<UUID, Instant> previousStarts = new java.util.HashMap<>();
        for (BookingServiceItem beforeItem : existing) {
            if (beforeItem.getAssignedStylistId() == null) continue;
            previousStarts.merge(beforeItem.getAssignedStylistId(), beforeItem.getServiceStart(),
                    (a, c) -> a.isBefore(c) ? a : c);
        }

        /*
         * Validate EVERY new slot before writing ANY of them.
         *
         * The same two-pass shape as create(), for the same reason: a booking with half its
         * services moved is worse than a refused reschedule, because it looks fine from the
         * outside and only shows up when two customers arrive for the same chair.
         */
        Map<UUID, BookingServiceItem> byId = existing.stream()
                .collect(Collectors.toMap(BookingServiceItem::getId, i -> i));
        List<UUID> resolvedStylists = new java.util.ArrayList<>();
        for (RescheduleItem move : req.items()) {
            BookingServiceItem item = byId.get(move.itemId());
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "UNKNOWN_ITEM: " + move.itemId() + " isn't part of this booking.");
            }
            if (move.start() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "START_REQUIRED");
            }
            if (move.start().isBefore(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "START_IN_THE_PAST: pick a time in the future.");
            }
            resolvedStylists.add(validateMovedSlot(b.getSalonId(), item, move));
        }

        /*
         * Session 38 (PENDING_WORK R6) — the booking must not collide with ITSELF.
         *
         * The availability check above excludes this whole booking, which is what makes moving
         * a service half an hour later possible at all. The cost of that exclusion is a blind
         * spot: it also stops the booking's OWN items blocking each other, so a two-service
         * booking could be moved onto a single overlapping time and every individual check
         * would pass.
         *
         * The result would be a customer booked into two chairs at once. Self-inflicted, and
         * visible on their own screen — but the server should not have accepted it, and the one
         * place that can see all the new times together is right here.
         *
         * Checked AFTER the availability pass and BEFORE anything is written, so a rejected
         * reschedule leaves the booking exactly as it was.
         */
        requireNoSelfOverlap(req.items(), byId, resolvedStylists);

        // Second pass: write. Everything above has already succeeded.
        List<RescheduleItem> moves = req.items();
        for (int i = 0; i < moves.size(); i++) {
            RescheduleItem move = moves.get(i);
            BookingServiceItem item = byId.get(move.itemId());
            // The stored duration, NOT a recomputed one — the customer is moving an appointment,
            // not rebuying it at today's menu.
            item.setServiceStart(move.start());
            item.setServiceEnd(move.start().plus(Duration.ofMinutes(item.getDurationShownMinutes())));
            item.setAssignedStylistId(resolvedStylists.get(i));
            items.save(item);
        }

        // Customer moves only. A salon moving its own bookings must not eat the customer's
        // allowance — they didn't ask for that change.
        if (!bySalon) {
            b.setRescheduleCount(b.getRescheduleCount() + 1);
        }
        b.touch();
        bookings.save(b);

        List<BookingServiceItem> updated = items.findByBookingId(bookingId);
        modifications.save(new com.bmp.booking.entities.BookingModification(
                bookingId, before, snapshotItems(updated),
                bySalon ? "salon" : "customer", actorUserId,
                req.reason()));

        Instant newStart = updated.stream().map(BookingServiceItem::getServiceStart)
                .min(Instant::compareTo).orElse(null);

        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("newStart", newStart == null ? null : newStart.toString());
        meta.put("rescheduleCount", b.getRescheduleCount());
        if (req.reason() != null) {
            meta.put("reason", req.reason());
        }
        recordEvent(bookingId, "RESCHEDULED", bySalon ? "salon" : "customer", actorUserId, meta);

        /*
         * Session 37 — "once they reschedule, the customer should see it".
         *
         * The app already reflects it: My bookings reads the items, which have moved. But a
         * customer who isn't looking at their phone learns nothing, and a salon-initiated move
         * they never saw is a customer arriving at the wrong time.
         *
         * Zero outbound calls — the V006 contact snapshot pays for itself again.
         */
        outbox.publish(new com.bmp.common.events.BookingRescheduled(
                b.getId(), b.getBookingRef(), b.getSalonNameSnapshot(), b.getCustomerId(),
                b.getCustomerName(), b.getCustomerPhone(), b.getCustomerEmail(),
                earliestOf(existing), newStart, req.reason(), bySalon ? "salon" : "customer"));

        /*
         * Session 53 — a move matters MORE to the stylist than the original booking did: an
         * appointment that quietly shifts is one they turn up for at the wrong time, or block out
         * twice. `previousStarts` was captured ABOVE, before the write pass mutated the entities —
         * see the comment there.
         *
         * A reschedule can also move the work to a different person. The one who LOST it has an
         * entry in the map and gets "moved from"; the one who GAINED it has none and gets a plain
         * "booked", which is exactly right for somebody seeing it for the first time.
         */
        List<ItemResponse> movedItems = updated.stream().map(this::toItemResponse).toList();
        notifyAssignedStylists(b, movedItems, "moved", b.getSalonNameSnapshot(), previousStarts);

        log.info("Booking {} rescheduled by {} to {} (customer moves so far: {})",
                b.getBookingRef(), bySalon ? "salon" : "customer", newStart, b.getRescheduleCount());

        return toResponse(b, updated.stream().map(this::toItemResponse).toList());
    }

    /**
     * Refuse a reschedule that would put one booking's services on top of each other.
     * Session 38 — closes the gap named in PENDING_WORK R6.
     *
     * <h2>Why the availability algorithm can't catch this</h2>
     * {@link #validateMovedSlot} passes {@code excludeBookingId}, which is what lets a service
     * move half an hour later without colliding with its own current slot. That exclusion is
     * necessary and it is also a blind spot: the booking's own items stop blocking each other,
     * so two services moved onto the same time both pass individually.
     *
     * <p>The customer would end up in two chairs at once. This is the only place that sees every
     * new time at once, so the check belongs here.
     *
     * <h2>Two services CAN share a time — if they're with different stylists</h2>
     * That is a real thing salons do: a manicure while a colour develops. So the refusal is
     * specifically <b>same stylist, overlapping window</b>, and a genuine parallel booking is
     * left alone. Refusing all overlap would break a legitimate and quite common arrangement.
     *
     * <p>O(n²) over the items of ONE booking — realistically two or three. A sort would be
     * faster and much harder to read for no measurable gain.
     */
    private void requireNoSelfOverlap(List<RescheduleItem> moves,
                                       Map<UUID, BookingServiceItem> byId,
                                       List<UUID> resolvedStylists) {
        for (int i = 0; i < moves.size(); i++) {
            Instant startA = moves.get(i).start();
            Instant endA = startA.plus(Duration.ofMinutes(
                    byId.get(moves.get(i).itemId()).getDurationShownMinutes()));
            UUID stylistA = resolvedStylists.get(i);

            for (int j = i + 1; j < moves.size(); j++) {
                UUID stylistB = resolvedStylists.get(j);
                // Different stylists can run in parallel — a manicure during a colour. Only a
                // clash on the SAME person is a real conflict.
                if (stylistA == null || !stylistA.equals(stylistB)) {
                    continue;
                }
                Instant startB = moves.get(j).start();
                Instant endB = startB.plus(Duration.ofMinutes(
                        byId.get(moves.get(j).itemId()).getDurationShownMinutes()));

                // Half-open intervals: an appointment ending at 11:30 and one starting at 11:30
                // do not overlap. The same convention the availability query uses
                // (serviceStart < windowEnd AND serviceEnd > windowStart).
                if (startA.isBefore(endB) && endA.isAfter(startB)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SERVICES_OVERLAP: " + byId.get(moves.get(i).itemId()).getNameSnapshot()
                            + " and " + byId.get(moves.get(j).itemId()).getNameSnapshot()
                            + " would run at the same time with the same stylist. "
                            + "Pick times that don't clash.");
                }
            }
        }
    }

    /**
     * Whether the SALON may move this booking without asking.
     *
     * <p>Read from the booking's frozen snapshot, not from today's policy — a salon that turns
     * the setting on this afternoon must not thereby acquire the right to move bookings made
     * under yesterday's terms.
     *
     * <p>When it's off, the refusal names the alternative rather than just saying no. A salon
     * that can't move a booking and isn't told what to do instead rings the customer, and BMP
     * ends up with no record of a change that definitely happened.
     */
    private void requireSalonMayReschedule(Booking b) {
        boolean allowed;
        try {
            com.fasterxml.jackson.databind.JsonNode snap = mapper.readTree(
                    b.getPolicySnapshot() == null || b.getPolicySnapshot().isBlank()
                            ? "{}" : b.getPolicySnapshot());
            allowed = snap.hasNonNull("salonCanRescheduleDirectly")
                    && snap.get("salonCanRescheduleDirectly").asBoolean(false);
        } catch (Exception e) {
            // Unreadable snapshot: fail CLOSED. Moving somebody's appointment on the strength of
            // a policy we can't read is the wrong way to be wrong.
            log.error("Booking {} has an unreadable policy_snapshot; refusing the salon-side "
                    + "reschedule. {}", b.getBookingRef(), e.toString());
            allowed = false;
        }
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SALON_RESCHEDULE_NOT_ALLOWED: this salon's policy doesn't permit moving a "
                    + "customer's booking directly. Call the customer and ask them to change it, "
                    + "or cancel with a reason — cancelling never charges them.");
        }
    }

    /**
     * Validate one moved item against the availability algorithm.
     *
     * <p>Uses the item's STORED duration — the same rule Session 30 established for creation.
     * Re-fetching today's duration would let a salon lengthening a service retroactively
     * invalidate a customer's existing slot.
     *
     * <h2>The booking is excluded from its own availability check</h2>
     * {@code excludeBookingId} is why rescheduling works at all. Moving a 60-minute service from
     * 11:00 to 11:30 asks "is 11:30–12:30 free?" — and the answer would be no, because
     * 11:00–12:00 is occupied by <b>the booking being moved</b>. Every short move, which is most
     * of them, would be refused with "that slot is taken", naming a slot the customer owns.
     *
     * <p>Excluding the whole booking rather than the one item is also deliberate: a cut plus a
     * colour move together and must not block each other mid-validation. Every OTHER booking
     * stays visible, so a reschedule still cannot land on another customer.
     *
     * <h2>Known limitation, stated rather than hidden</h2>
     * Because the whole booking is excluded, two items of the SAME booking could in principle be
     * moved onto each other — nothing here checks the new times against one another. A customer
     * would be booking themselves into two chairs at once, which is self-inflicted and visible
     * on their own screen, but the UI should still prevent offering it. Left out of the server
     * for now rather than half-implemented; noted in PENDING_WORK.
     */
    private UUID validateMovedSlot(UUID salonId, BookingServiceItem item, RescheduleItem move) {
        UUID stylistId = move.stylistId() != null ? move.stylistId() : item.getAssignedStylistId();
        ItemRequest asRequest = new ItemRequest(
                item.getServiceId(), stylistId,
                stylistId != null ? "specific_stylist" : "any_available",
                move.start(), null, 0L, 0);
        return resolveAndValidateSlot(salonId, asRequest, item.getDurationShownMinutes(),
                item.getBookingId());
    }

    /**
     * The item times, as JSON, for {@code booking_modification}.
     *
     * <p>Times and stylist only. Not the whole entity: a modification row is a record of WHAT
     * MOVED, and dumping price and status into it would mean a table that quietly accumulates
     * copies of customer-adjacent data with no retention story attached.
     */
    private String snapshotItems(List<BookingServiceItem> list) {
        List<Map<String, Object>> rows = list.stream()
                .sorted(java.util.Comparator.comparing(BookingServiceItem::getServiceStart))
                .map(i -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("itemId", i.getId().toString());
                    m.put("serviceName", i.getNameSnapshot());
                    m.put("start", i.getServiceStart().toString());
                    m.put("end", i.getServiceEnd().toString());
                    m.put("stylistId", i.getAssignedStylistId() == null
                            ? null : i.getAssignedStylistId().toString());
                    return m;
                })
                .toList();
        try {
            return mapper.writeValueAsString(rows);
        } catch (Exception e) {
            // The column is NOT NULL and this is an audit record — an empty object is a bad row
            // but a lost reschedule is a worse outcome, so it's logged loudly and written.
            log.error("Could not serialise a booking_modification snapshot — writing an empty "
                    + "one. The move happened; the record of what moved is incomplete. {}", e.toString());
            return "{}";
        }
    }

    private Instant earliestOf(List<BookingServiceItem> list) {
        return list.stream().map(BookingServiceItem::getServiceStart).min(Instant::compareTo).orElse(null);
    }

    /**
     * One customer, as this salon knows them. Session 36.
     *
     * <h2>The one thing to get right here</h2>
     * Both ids go into every query. {@code findBySalonIdAndCustomerIdOrderByCreatedAtDesc} and
     * {@code customerStats} each take the pair, and neither has a single-argument variant on the
     * salon side — deliberately, so the boundary is structural rather than a rule somebody has
     * to remember.
     *
     * <p>The tempting shortcut was to call {@code findByCustomerId} and filter by salon in Java.
     * That would have loaded <b>every booking that customer has made anywhere on BMP</b> before
     * the filter ran, and "we filtered it in the UI" is not a defence once the JSON has crossed
     * the wire. Where a customer went last month is another salon's commercial data and the
     * customer's own business.
     *
     * <p>{@code salonId} comes from the caller's JWT, never from the request — see the
     * controller. So there is no combination of parameters that reads another shop's data.
     *
     * <h2>Why the identity comes off the bookings, not from bmp-user</h2>
     * The name and (masked) number are read from the most recent booking's V006 snapshot rather
     * than resolved live. Two reasons: this screen must work when bmp-user is restarting, and —
     * more importantly — the salon is entitled to what they were told at booking time, not to a
     * live view of a person's current record on the platform. A salon-facing screen that queries
     * bmp-user directly is one step from being a customer directory.
     */
    /**
     * Every visit a COUNTER customer has made to this salon. Session 52.
     *
     * <h2>Why this isn't {@link #customerAtSalon}</h2>
     * That method takes a BMP {@code customerId} and builds a rich card — total spend, no-show
     * count, usual stylist — from the {@code customerStats} projection, which is keyed on
     * {@code customer_id}. A counter customer has none: their id lives in another service's table
     * and their bookings carry {@code customer_id = NULL} (V009).
     *
     * <p>Rather than widen that projection and end up with a stats query that means two different
     * things depending on which id is populated, this returns the visits and nothing else. The
     * counts a receptionist actually wants at the desk — how many times, when last —
     * are already denormalised onto {@code salon_customer} itself, and are cheaper there.
     *
     * <h2>404 on nothing, same as the sibling</h2>
     * An empty list would let one salon confirm whether an arbitrary id exists by watching which
     * come back 200. The pair (salon, customer) has to match.
     */
    @Transactional(readOnly = true)
    public PagedBookings salonCustomerBookings(UUID salonId, UUID salonCustomerId, int page, int size) {
        Page<Booking> p = bookings.findBySalonIdAndSalonCustomerIdOrderByCreatedAtDesc(
                salonId, salonCustomerId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));

        if (p.getTotalElements() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_BOOKINGS_AT_THIS_SALON");
        }

        List<BookingResponse> content = p.getContent().stream()
                .map(b -> toResponse(b, items.findByBookingId(b.getId()).stream()
                        .map(this::toItemResponse).toList()))
                .toList();
        return new PagedBookings(content, page, size, p.getTotalElements());
    }

    public CustomerAtSalonResponse customerAtSalon(UUID salonId, UUID customerId, int page, int size) {
        Page<Booking> p = bookings.findBySalonIdAndCustomerIdOrderByCreatedAtDesc(
                salonId, customerId, PageRequest.of(page, size));

        if (p.getTotalElements() == 0) {
            // Not "empty results" — this customer has never been to this salon. Returning an
            // empty summary would let a salon confirm whether an arbitrary user id exists on
            // BMP by watching which ids come back 200 and which 404.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "NO_BOOKINGS_AT_THIS_SALON");
        }

        List<BookingResponse> content = p.getContent().stream()
                .map(b -> toResponse(b, items.findByBookingId(b.getId()).stream()
                        .map(this::toItemResponse).toList()))
                .toList();

        var stats = bookings.customerStats(salonId, customerId);

        // The most recent booking carries the freshest snapshot — a customer who changed their
        // name or number between visits should read as their current self, not their first one.
        Booking latest = p.getContent().get(0);

        return new CustomerAtSalonResponse(
                customerId,
                latest.getCustomerName(),
                maskPhone(latest.getCustomerPhone()),
                nz(stats == null ? null : stats.getTotalBookings()),
                nz(stats == null ? null : stats.getCompletedVisits()),
                nz(stats == null ? null : stats.getCancelledCount()),
                nz(stats == null ? null : stats.getNoShowCount()),
                nz(stats == null ? null : stats.getTotalSpentPaise()),
                stats == null ? null : stats.getFirstVisit(),
                stats == null ? null : stats.getLastVisit(),
                usualStylist(salonId, customerId),
                new PagedBookings(content, page, size, p.getTotalElements()));
    }

    /** A projection over zero rows can still hand back nulls; a screen wanting zero gets zero. */
    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * The stylist this customer has completed the most services with, or null.
     *
     * <h2>Null on a tie, deliberately</h2>
     * "Usually sees Meera" is worth putting on a card only if it is true. With two visits to two
     * different stylists there is no usual stylist, and picking whichever the sort happened to
     * put first invents a preference the customer never expressed — which a receptionist will
     * then act on out loud. Saying nothing is the honest output, and the card omits the line.
     *
     * <h2>Completed items only</h2>
     * A cancelled booking says nothing about who they like. Counting it would let one cancelled
     * appointment with a stylist they specifically avoided outrank three real visits.
     *
     * <p>Bounded by construction: this is one customer's history at one salon, so the item list
     * is tens of rows even for a regular of several years. Pushing it into SQL would be a
     * premature optimisation on a query that runs when somebody taps a name.
     */
    private UUID usualStylist(UUID salonId, UUID customerId) {
        List<Booking> completed = bookings
                .findBySalonIdAndCustomerIdOrderByCreatedAtDesc(salonId, customerId, PageRequest.of(0, 200))
                .getContent().stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .toList();
        if (completed.isEmpty()) {
            return null;
        }

        Map<UUID, Long> tally = completed.stream()
                .flatMap(b -> items.findByBookingId(b.getId()).stream())
                .map(BookingServiceItem::getAssignedStylistId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        if (tally.isEmpty()) {
            return null;
        }

        long best = tally.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<UUID> leaders = tally.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .toList();
        return leaders.size() == 1 ? leaders.get(0) : null;
    }

    /**
     * A salon's whole day, as service blocks plus a summary.
     *
     * <p>"Day" means the salon's local calendar day (Asia/Kolkata via {@link BmpTimeZone}),
     * converted to a UTC instant window for the query — NOT the server's day. A 9pm booking on
     * the 3rd must appear on the 3rd for the person standing in the salon, whatever timezone
     * the JVM happens to think it's in.
     *
     * <p>Two queries, not N+1: the items for the window, then their parent bookings in one
     * {@code findAllById}. A single salon-day is naturally bounded (tens of rows), so this is
     * comfortably within budget.
     */
    public SalonDayResponse salonDay(UUID salonId, LocalDate date, UUID stylistId) {
        Instant from = date.atStartOfDay(BmpTimeZone.ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(BmpTimeZone.ZONE).toInstant();

        List<BookingServiceItem> dayItems = items.findSalonDayItems(salonId, from, to, stylistId);
        Map<UUID, Booking> parents = bookings.findAllById(
                        dayItems.stream().map(BookingServiceItem::getBookingId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Booking::getId, b -> b));

        List<ScheduleEntryResponse> entries = dayItems.stream()
                .map(i -> {
                    Booking b = parents.get(i.getBookingId());
                    // A missing parent would mean an orphaned item — impossible via the API,
                    // but skip rather than NPE if data was hand-edited.
                    if (b == null) return null;
                    return new ScheduleEntryResponse(
                            b.getId(), b.getBookingRef(), b.getStatus().name(), b.getCustomerId(),
                            // Session 34. Masked here, in the service, NOT in the UI — a client
                            // that masks is a client that received the real number, and anyone
                            // with the browser's network tab can read it.
                            b.getCustomerName(), maskPhone(b.getCustomerPhone()),
                            i.getId(), i.getNameSnapshot(), i.getAssignedStylistId(), i.getSelectionType(),
                            i.getServiceStart(), i.getServiceEnd(), i.getDurationShownMinutes(),
                            i.getPricePaiseSnapshot().paise(), i.getItemStatus());
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        // Summarise at BOOKING level, not item level: three services for one customer is one
        // booking, and counting it as three would make every number on the desk wrong.
        List<Booking> distinctBookings = entries.stream()
                .map(ScheduleEntryResponse::bookingId).distinct()
                .map(parents::get).filter(java.util.Objects::nonNull).toList();

        int completed = (int) distinctBookings.stream().filter(b -> b.getStatus() == BookingStatus.COMPLETED).count();
        int cancelled = (int) distinctBookings.stream().filter(b -> b.getStatus() == BookingStatus.CANCELLED).count();
        int noShow = (int) distinctBookings.stream().filter(b -> b.getStatus() == BookingStatus.NO_SHOW).count();
        int upcoming = (int) distinctBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.PENDING || b.getStatus() == BookingStatus.CONFIRMED)
                .count();

        // Expected revenue excludes money that isn't coming: cancelled and no-show.
        long expected = distinctBookings.stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED && b.getStatus() != BookingStatus.NO_SHOW)
                .mapToLong(b -> b.getFinalAmountPaise().paise())
                .sum();

        SalonDaySummaryResponse summary = new SalonDaySummaryResponse(
                date.toString(), distinctBookings.size(), completed, cancelled, noShow, upcoming, expected);
        return new SalonDayResponse(summary, entries);
    }

    // ══ the stylist's own schedule ════════════════════════════════════════════════════════════
    //
    // Session 48. Everything a stylist sees about their bookings goes through toStylistEntry, and
    // that mapper cannot emit a customer id, phone, email, surname or any amount of money —
    // StylistScheduleEntry has no components to put them in.
    //
    // WHY A SEPARATE PATH AT ALL. The stylist dashboard used to call salonDay, which is
    // @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')") — so it returned 403 to every real
    // stylist and the screen only ever worked in mock mode. Fixing that by adding STYLIST to that
    // annotation would have handed them customerPhone and pricePaise on the same response.

    /**
     * The single mapper for stylist-facing appointments.
     *
     * <p><b>Read the field list before adding anything.</b> Everything omitted here is omitted on
     * purpose and is documented on {@link StylistScheduleEntry}. If a future change needs the
     * customer's identity on a stylist screen, that is a product decision to take deliberately,
     * not a field to widen in passing.
     */
    private StylistScheduleEntry toStylistEntry(BookingServiceItem i, Booking b) {
        return new StylistScheduleEntry(
                b.getId(), b.getBookingRef(), b.getStatus().name(),
                firstNameOnly(b.getCustomerName()),
                i.getId(), i.getNameSnapshot(),
                i.getServiceStart(), i.getServiceEnd(), i.getDurationShownMinutes(),
                i.getItemStatus(),
                true);
    }

    /**
     * "Priya Menon" → "Priya". Null and blank stay null.
     *
     * <h2>Why the surname is dropped in the SERVICE, not the UI</h2>
     * Session 34 learned this on the phone number: a client that masks is a client that received
     * the real value, and anyone with a browser network tab can read it. The full name never
     * leaves this method.
     *
     * <p>A single-word name ("Priya") is returned unchanged — it is already just a first name.
     * A name that is only a surname is a case this cannot detect and does not try to.
     */
    private static String firstNameOnly(String fullName) {
        if (fullName == null) return null;
        String t = fullName.trim();
        if (t.isEmpty()) return null;
        int space = t.indexOf(' ');
        return space < 0 ? t : t.substring(0, space);
    }

    /**
     * One day of a stylist's own appointments.
     *
     * @param salonId   resolved from the caller's token by the controller, never sent by them
     * @param stylistId likewise — see SalonAvailabilityClient.stylistByUser
     */
    public StylistDayResponse stylistDay(UUID salonId, UUID stylistId, LocalDate date) {
        Instant from = date.atStartOfDay(BmpTimeZone.ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(BmpTimeZone.ZONE).toInstant();

        // Reuses the day query with its stylist filter — same rows the manager sees for this
        // stylist, mapped through a type that cannot carry the sensitive columns.
        List<BookingServiceItem> dayItems = items.findSalonDayItems(salonId, from, to, stylistId);
        Map<UUID, Booking> parents = bookings.findAllById(
                        dayItems.stream().map(BookingServiceItem::getBookingId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Booking::getId, b -> b));

        List<StylistScheduleEntry> entries = dayItems.stream()
                .map(i -> {
                    Booking b = parents.get(i.getBookingId());
                    return b == null ? null : toStylistEntry(i, b);
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        // Counted at BOOKING level, same as the manager's summary: three services for one
        // customer is one appointment, and counting three would make the number meaningless.
        List<Booking> distinct = entries.stream()
                .map(StylistScheduleEntry::bookingId).distinct()
                .map(parents::get).filter(java.util.Objects::nonNull).toList();

        int completed = (int) distinct.stream().filter(b -> b.getStatus() == BookingStatus.COMPLETED).count();
        int cancelled = (int) distinct.stream().filter(b -> b.getStatus() == BookingStatus.CANCELLED).count();

        /*
         * Minutes booked, NOT money earned.
         *
         * A stylist wants to know how full their day is, and this answers that without going
         * anywhere near the salon's takings. Cancelled items are excluded — that time is free.
         */
        int bookedMinutes = entries.stream()
                .filter(e -> !BookingStatus.CANCELLED.name().equals(e.bookingStatus()))
                .mapToInt(StylistScheduleEntry::durationMinutes)
                .sum();

        return new StylistDayResponse(date, distinct.size(), completed, cancelled, bookedMinutes, entries);
    }

    /** A stylist's own forward queue. */
    public PagedStylistBookings stylistUpcoming(UUID salonId, UUID stylistId, int page, int size) {
        var p = items.findUpcomingForStylist(salonId, stylistId, Instant.now(),
                org.springframework.data.domain.PageRequest.of(page, size));
        return pageOfStylistEntries(p, page, size);
    }

    /** A stylist's own past work at this salon. */
    public PagedStylistBookings stylistHistory(UUID salonId, UUID stylistId, int page, int size) {
        var p = items.findPastForStylist(salonId, stylistId, Instant.now(),
                org.springframework.data.domain.PageRequest.of(page, size));
        return pageOfStylistEntries(p, page, size);
    }

    private PagedStylistBookings pageOfStylistEntries(
            org.springframework.data.domain.Page<BookingServiceItem> p, int page, int size) {
        Map<UUID, Booking> parents = bookings.findAllById(
                        p.getContent().stream().map(BookingServiceItem::getBookingId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Booking::getId, b -> b));
        List<StylistScheduleEntry> rows = p.getContent().stream()
                .map(i -> {
                    Booking b = parents.get(i.getBookingId());
                    return b == null ? null : toStylistEntry(i, b);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return new PagedStylistBookings(rows, page, size, p.getTotalElements());
    }

    /**
     * Paged booking history for a salon — the "search everything" view behind the day view.
     *
     * <h2>Session 44: filters</h2>
     * <ul>
     *   <li>{@code stylistId} — "how has Ravi's month been?". Matches bookings Ravi worked
     *       <b>any part of</b>: the stylist is on the ITEM, and one booking can span two chairs.</li>
     *   <li>{@code search} — the owner typing a customer's name or a booking reference.
     *       <b>Not phone.</b> V006 deliberately refused an index on {@code customer_phone}
     *       because phone lookup is an effective way to enumerate the platform's customers and
     *       belongs in bmp-admin where it's audited. Matching on it here would route around that
     *       decision one salon at a time. Name search grants nothing new — the salon already
     *       reads those names off its own desk every day.</li>
     * </ul>
     *
     * <p>All filters are ANDed with {@code salonId}, which the caller does not supply: it comes
     * from the JWT via {@code requireSalonScope}. The search can only ever narrow rows the salon
     * already holds.
     */
    public PagedBookings salonList(UUID salonId, String status, UUID stylistId, String search,
                                    int page, int size) {
        // Blank is not a filter. A search box that has been focused and cleared sends "", and
        // treating that as "match nothing containing empty string" would work by accident today
        // and break the day someone changes the LIKE. Normalise it to absent.
        String q = (search == null || search.isBlank()) ? null : search.trim();
        BookingStatus st = status == null ? null : BookingStatus.valueOf(status);

        Page<Booking> p = bookings.searchSalonHistory(
                salonId, st, stylistId, q, PageRequest.of(page, size));

        List<BookingResponse> content = p.getContent().stream()
                .map(b -> toResponse(b, items.findByBookingId(b.getId()).stream().map(this::toItemResponse).toList()))
                .toList();
        return new PagedBookings(content, page, size, p.getTotalElements());
    }

    /**
     * A salon-side status change: arrived → in service → completed, or no-show.
     *
     * <p>The legal moves are NOT decided here — {@link BookingStatus} owns the state machine
     * and this method just asks it, with {@code Actor.SALON}. That's why an illegal move comes
     * back as a 409 with the machine's own message rather than a bespoke rule invented in the
     * service layer. Adding a new transition means editing the enum, which is exactly where
     * someone reviewing "who can do what to a booking" will look.
     *
     * <p><b>Known operational gap:</b> bookings currently sit in PENDING forever, because
     * PENDING→CONFIRMED is a SYSTEM transition driven by the Razorpay webhook (Phase 3). Until
     * payments land, nothing reaches CONFIRMED, so these actions are unreachable in practice.
     * Deliberate: adding a manual confirm would create a second path into CONFIRMED that must
     * not disagree with the webhook once it exists. The UI disables the buttons and explains why.
     */
    @Transactional
    public BookingResponse salonTransition(UUID bookingId, BookingStatus target, UUID actorUserId, String note) {
        Booking b = bookings.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        try {
            b.getStatus().assertTransition(target, BookingStatus.Actor.SALON);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        b.setStatus(target);
        b.touch();

        // Completing the booking closes its items too, so the availability algorithm and any
        // future reporting agree with the booking's own status.
        if (target == BookingStatus.COMPLETED) {
            items.findByBookingId(bookingId).forEach(i -> i.setItemStatus("completed"));
        }

        recordEvent(bookingId, target.name(), "salon", actorUserId,
                note == null || note.isBlank() ? Map.of() : Map.of("note", note));

        /*
         * Session 34 — completion is the one moment BMP has earned the right to ask for
         * something, so it is the event with the most future consumers: review prompts,
         * referral nudges, and settlement once payments exist. That is precisely why it is an
         * event rather than a direct call from the desk.
         *
         * Only COMPLETED publishes. The other salon transitions deliberately don't:
         *
         *   ARRIVED / IN_SERVICE — the customer is standing in the shop. Texting someone who is
         *     three feet from the person who pressed the button is noise, and notification
         *     fatigue is how the messages that DO matter stop being read.
         *
         *   NO_SHOW — an accusation. "You didn't turn up" sent automatically, on the salon's
         *     unilateral say-so, with no dispute path, is a message that will sometimes be
         *     wrong and will always be resented. It needs a policy and a right of reply before
         *     it needs code.
         */
        if (target == BookingStatus.COMPLETED) {
            outbox.publish(new com.bmp.common.events.BookingCompleted(
                    b.getId(), b.getBookingRef(), b.getSalonId(), b.getSalonNameSnapshot(),
                    b.getCustomerId(), b.getCustomerName(), b.getCustomerPhone(),
                    b.getCustomerEmail(), b.getFinalAmountPaise().paise()));
        }

        List<ItemResponse> itemResponses = items.findByBookingId(bookingId).stream().map(this::toItemResponse).toList();
        return toResponse(b, itemResponses);
    }

    /**
     * The reasons a salon may give for needing a customer's real phone number.
     *
     * <h2>Why a fixed set rather than free text</h2>
     * A free-text box is filled with "." within a week, and a reveal nobody can review later is
     * a reveal that may as well not have been recorded. These five cover what actually happens
     * at a salon counter; {@code OTHER} exists so the list is never a reason to route around
     * the feature, and it requires a note precisely because "other" alone says nothing.
     *
     * <p>Adding a reason is a one-line change. Removing one is not — old {@code booking_events}
     * rows carry the string, and a reader six months from now needs it to still mean something.
     */
    public enum ContactRevealReason {
        /** The salon is behind and the customer is on their way. The commonest case by far. */
        RUNNING_LATE,
        /** A stylist called in sick. The call is an apology and a reschedule. */
        STYLIST_UNAVAILABLE,
        /** Checking a booking is still happening — usually a large or first-time one. */
        CONFIRM_BOOKING,
        /** The customer didn't arrive and the salon wants to ask before marking a no-show. */
        CUSTOMER_UNREACHABLE,
        /** Anything else. Requires a note. */
        OTHER;

        static ContactRevealReason parse(String raw) {
            if (raw == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED");
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "UNKNOWN_REASON: expected one of running_late, stylist_unavailable, "
                        + "confirm_booking, customer_unreachable, other");
            }
        }
    }

    /**
     * Hand the salon the customer's real phone number, and write down that it happened.
     *
     * <h2>Why this is an endpoint and not a field</h2>
     * The salon day view shows {@code 98765 4••••}. It could just as easily show the whole
     * number — and then every screen a manager opens would be a downloadable customer list.
     * Two consequences, and the second is the one that survives a change of ownership:
     *
     * <ul>
     *   <li>A salon that can harvest BMP's customers can take them off-platform. That is the
     *       commission walking out the door, and it is the single most common way a marketplace
     *       gets disintermediated.</li>
     *   <li>Under DPDP, BMP is the data fiduciary for that leak regardless of who did the
     *       exporting.</li>
     * </ul>
     *
     * <p>Making it a deliberate action costs a salon with a real reason one extra tap. It costs
     * a salon quietly building a contact list a permanent, per-customer record of doing so.
     *
     * <h2>Where the record goes, and why it's an unusual choice</h2>
     * {@code booking_events} — the booking's own append-only trail — <b>not</b> bmp-admin's
     * {@code audit_log}. Deliberate, and the reason is not convenience:
     *
     * <p>{@code GET /bookings/{id}/events} is readable by <b>the customer</b>. So a customer can
     * see, in their own app, that the salon looked up their number and why. An audit trail only
     * BMP can read protects BMP; one the data subject can read protects them. Given this
     * endpoint exists to hand out a personal phone number, it should be the second kind.
     *
     * <p>The cost is that it isn't in the console's audit view. If ops ever need a
     * cross-salon "who is revealing the most numbers" report, that is a query over
     * {@code booking_events}, not a reason to move the record somewhere the customer can't see.
     *
     * <h2>What this does not yet do</h2>
     * There is no rate limit. A determined salon could open every booking in their own history
     * and reveal each one — bounded by their own customers, logged per booking, and visible to
     * each of those customers, but not prevented. A cap (say twenty a day, then a support
     * conversation) belongs here once there is real traffic to calibrate against; inventing the
     * number now would just produce a limit that is wrong in both directions.
     *
     * @return the number, or a null phone when BMP holds none — the caller distinguishes
     *         "we won't tell you" (403, thrown before this runs) from "we don't know" (null)
     */
    @Transactional
    public ContactRevealResponse revealCustomerContact(UUID bookingId, UUID actorUserId,
                                                        ContactRevealRequest req) {
        Booking b = bookings.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        ContactRevealReason reason = ContactRevealReason.parse(req.reason());
        String note = req.note() == null ? null : req.note().trim();
        if (reason == ContactRevealReason.OTHER && (note == null || note.length() < 5)) {
            // "Other" with no explanation is the same as no reason at all, and it would become
            // the default choice the moment it's the cheapest one.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "NOTE_REQUIRED: tell us why, in a few words, when the reason is 'other'.");
        }

        // Written BEFORE the number is returned. If the write fails the transaction rolls back
        // and the salon gets nothing — which is the correct direction for this to fail in.
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("reason", reason.name().toLowerCase(java.util.Locale.ROOT));
        if (note != null && !note.isBlank()) {
            metadata.put("note", note);
        }
        // Recorded so the trail says WHICH number was handed over, without the trail itself
        // becoming a second copy of the phone book.
        metadata.put("hadPhone", b.getCustomerPhone() != null);
        recordEvent(bookingId, "CONTACT_REVEALED", "salon", actorUserId, metadata);

        log.info("Customer contact for booking {} revealed to salon staff {} (reason={})",
                b.getBookingRef(), actorUserId, reason);

        return new ContactRevealResponse(b.getCustomerPhone(), b.getCustomerName(), Instant.now());
    }

    /** Who a booking belongs to — used by the controller's authorization checks. */
    public UUID salonIdOf(UUID bookingId) {
        return bookings.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"))
                .getSalonId();
    }

    /** Who booked it — used by the controller's authorization checks. */
    public UUID customerIdOf(UUID bookingId) {
        return bookings.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"))
                .getCustomerId();
    }

    public List<EventResponse> listEvents(UUID bookingId) {
        return events.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(e -> new EventResponse(e.getEventType(), e.getActorType(), e.getActorId(), e.getCreatedAt()))
                .toList();
    }

    /**
     * Shows enough of a phone number to be useful, not enough to be a customer list.
     *
     * <p>Session 34. Keeps the leading digits (which let a manager match a walk-in against a
     * booking, and confirm a number a customer reads out) and hides the last four — the part
     * that makes the number dialable or exportable.
     *
     * <p><b>Masked in the service, not the UI.</b> A client that masks is a client that received
     * the real number; anyone with a browser network tab reads it. Masking at the edge that
     * knows who is asking is the only masking that is worth anything.
     *
     * <p>Why mask at all, when the salon is about to serve this person: a day view that shows
     * full numbers is a downloadable customer list. A salon that can harvest BMP's customers can
     * take them off-platform — which is the commission walking out the door — and under DPDP,
     * BMP is the data fiduciary for that leak either way. When a salon genuinely needs to call
     * (running late, stylist off sick), that should be a deliberate, audited action rather than
     * a side effect of opening a screen. That endpoint is the next piece of this work.
     *
     * <p>Anything shorter than 6 digits is returned as-is: partially masking a number that is
     * already too short to identify anyone just makes it unrecognisable to the manager without
     * protecting anything.
     */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return phone;
        }
        return phone.substring(0, phone.length() - 4) + "••••";
    }

    private void recordEvent(UUID bookingId, String eventType, String actorType, UUID actorId, Map<String, Object> metadata) {
        String json;
        try {
            json = mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            json = "{}";
        }
        events.save(new BookingEvents(bookingId, eventType, actorType, actorId, json));
    }

    private ItemResponse toItemResponse(BookingServiceItem i) {
        return new ItemResponse(i.getId(), i.getServiceId(), i.getAssignedStylistId(), i.getSelectionType(),
                i.getServiceStart(), i.getServiceEnd(), i.getNameSnapshot(), i.getPricePaiseSnapshot().paise(),
                i.getDurationShownMinutes(), i.getItemStatus());
    }

    private BookingResponse toResponse(Booking b, List<ItemResponse> itemResponses) {
        return new BookingResponse(b.getId(), b.getBookingRef(), b.getSalonId(), b.getCustomerId(),
                b.getStatus().name(), b.getFinalAmountPaise().paise(), b.getTotalRefundedPaise().paise(),
                b.getCreatedAt(), itemResponses,
                b.getCouponId(),
                // Defensive: bookings created before V005 have these defaulted by the migration,
                // but a null here would be a 500 on a screen that just wants to show a total.
                b.getGrossAmountPaise() == null ? b.getFinalAmountPaise().paise() : b.getGrossAmountPaise().paise(),
                b.getDiscountPaise() == null ? 0L : b.getDiscountPaise().paise(),
                // Session 35. Masked, always — this record is served to the salon's history list
                // as well as to the customer's own. The real number is behind reveal-contact.
                b.getCustomerName(), maskPhone(b.getCustomerPhone()));
    }
}
