package com.bmp.booking.controllers;

import com.bmp.booking.api.BookingStatus;
import com.bmp.booking.dto.BookingDtos.*;
import com.bmp.booking.services.BookingService;
import com.bmp.common.security.AuthenticatedUser;
import com.bmp.common.time.BmpTimeZone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * booking_schema.booking CRUD + append-only booking_events read, and (Session 16) the salon
 * side of the same data: a manager's day view and the operational status transitions.
 *
 * <h2>Authorization (Session 16)</h2>
 * Until this pass every endpoint here was open to ANY valid token — any logged-in customer
 * could read, and cancel, anybody else's booking just by knowing an id. That is now closed.
 * Two principals may touch a booking:
 *
 * <ul>
 *   <li><b>The customer who made it</b> — reads and cancels their own, nothing else.</li>
 *   <li><b>Staff of the salon it belongs to</b> (SALON_OWNER / MANAGER, matched on the
 *       {@code salonId} claim in their JWT) — reads the salon's bookings and drives the
 *       operational transitions.</li>
 * </ul>
 *
 * <p>The salonId claim is re-resolved from the staff table on every token mint (bmp-auth's
 * {@code resolveSalonScope}), so a manager who was removed loses access on their next refresh
 * without anything here needing to know about it.
 *
 * <p>The checks live in method bodies rather than {@code @PreAuthorize} SpEL because they need
 * a database lookup ("whose booking is this?"); expressing that in an annotation string would
 * be unreadable and untestable.
 */
@Tag(name = "Bookings", description = "booking + booking_service_item CRUD, append-only booking_events, and the salon-side day view + operational transitions. Customers see only their own bookings; salon staff see only their own salon's.")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService service;
    /** Session 48 — the stylist-income aggregation. A read-only query, no service layer needed. */
    private final com.bmp.booking.repositories.BookingServiceItemRepository items;

    /** Session 48 — resolving a stylist's userId to their profile + salon. See stylistScope. */
    private final com.bmp.booking.client.SalonAvailabilityClient salonClient;

    /** Session 52 — the counter/phone booking flow. See CounterBookingService for the ordering. */
    private final com.bmp.booking.services.CounterBookingService counter;

    public BookingController(BookingService service,
                              com.bmp.booking.repositories.BookingServiceItemRepository items,
                              com.bmp.booking.client.SalonAvailabilityClient salonClient,
                              com.bmp.booking.services.CounterBookingService counter) {
        this.service = service;
        this.items = items;
        this.salonClient = salonClient;
        this.counter = counter;
    }

    // ---- customer-facing -----------------------------------------------------------------

    @Operation(summary = "Create a booking", description = "Session 10: every item's requested slot is validated against bmp-salon-service's availability algorithm before anything is written — a stale slot (someone else booked it first) returns 409 SLOT_NOT_AVAILABLE rather than silently double-booking. An item with no stylistId (any_available) gets one auto-assigned from whoever still has that exact slot free. Session 16: you can only book for yourself.")
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @Valid @RequestBody CreateBookingRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        // Without this, any customer could create bookings in someone else's name.
        requireSelfOrService(req.customerId(), caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a booking by id", description = "The customer who made it, or staff of the salon it belongs to. 403 otherwise.")
    @GetMapping("/{bookingId}")
    public BookingResponse getById(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireCustomerOrSalonStaff(bookingId, caller);
        return service.getById(bookingId);
    }

    @Operation(summary = "List a customer's bookings", description = "Paginated, optionally filtered by status. You can only list your own.")
    @GetMapping
    public PagedBookings list(@RequestParam UUID customerId,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrService(customerId, caller);
        return service.list(customerId, status, page, size);
    }

    @Operation(
        summary = "Cancel a booking (customer)",
        description = "PENDING and CONFIRMED can both be cancelled. Session 37: the salon's frozen "
            + "cancellation terms now actually apply — the fee band is computed against the "
            + "ORIGINAL appointment time and written to the booking. Call the preview first; it "
            + "runs the identical calculation, so what the customer is shown and what is recorded "
            + "cannot disagree.")
    @PostMapping("/{bookingId}/cancel")
    public BookingResponse cancel(
            @PathVariable UUID bookingId,
            @RequestBody CancelRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrService(service.customerIdOf(bookingId), caller);
        return service.cancel(bookingId, req, false, caller == null ? null : caller.userId());
    }

    @Operation(
        summary = "What would cancelling cost?",
        description = "Read-only. Runs the same calculation the real cancellation does, so the "
            + "preview and the charge can't drift apart. Returns the band, the fee in paise, and "
            + "the salon's terms as a sentence. The refund figure is ADVISORY until payments exist.")
    @GetMapping("/{bookingId}/cancel-preview")
    public CancelPreviewResponse cancelPreview(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        // Same gate as cancelling. A fee preview reveals the booking's value and the salon's
        // terms; it is not more public than the thing it previews.
        requireCustomerOrSalonStaff(bookingId, caller);
        return service.previewCancellation(bookingId);
    }

    @Operation(
        summary = "Can this booking still be moved?",
        description = "Ask before showing a Reschedule button, so the app never offers an action "
            + "that is about to 409. The refusal is the salon's own terms in plain words — a "
            + "customer told 'changes need 24 hours' notice' can act on it.")
    @GetMapping("/{bookingId}/reschedule-eligibility")
    public RescheduleEligibility rescheduleEligibility(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireCustomerOrSalonStaff(bookingId, caller);
        return service.rescheduleEligibility(bookingId);
    }

    /**
     * The customer moves their own appointment. Session 37.
     *
     * <h2>Not a status transition</h2>
     * The booking stays PENDING (or CONFIRMED). Rescheduling moves the times on
     * {@code booking_service_item} and appends to {@code booking_modification} — a table that
     * existed from V002 and had never had a row written to it.
     *
     * <h2>Every limit is the SALON'S, frozen at booking time</h2>
     * Notice period and reschedule count come out of this booking's {@code policy_snapshot},
     * never from today's policy. A salon tightening its rules on Tuesday must not strand a
     * customer who booked on Monday.
     *
     * <p>The reschedule does NOT reset the cancellation clock — see V007. Otherwise a customer
     * could reschedule out of the fee window an hour before their slot and then cancel free.
     */
    @Operation(
        summary = "Move a booking to a new time (customer)",
        description = "Every service on the booking must be given a new time. The new slots are "
            + "validated against the availability algorithm exactly like a new booking, so this "
            + "can't be used to double-book a stylist. Price, duration and the coupon are NOT "
            + "re-derived — the customer is moving an appointment, not rebuying it. Limited by the "
            + "salon's notice period and reschedule cap, both frozen at booking time. 409 with the "
            + "salon's terms in words when refused.")
    @PostMapping("/{bookingId}/reschedule")
    public BookingResponse reschedule(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrService(service.customerIdOf(bookingId), caller);
        return service.reschedule(bookingId, req, false, caller == null ? null : caller.userId());
    }

    @Operation(summary = "List a booking's event history", description = "Append-only audit trail (booking_events) — every status transition, never mutated or deleted. Visible to the customer and to the salon's staff.")
    @GetMapping("/{bookingId}/events")
    public List<EventResponse> events(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireCustomerOrSalonStaff(bookingId, caller);
        return service.listEvents(bookingId);
    }

    // ---- salon desk (Session 16) ----------------------------------------------------------
    //
    // The salon is never named in the request: it comes from the caller's JWT salonId claim.
    // That's not a shortcut — it means there is no parameter to tamper with, so one salon's
    // manager cannot read another salon's day by editing a URL.

    /**
     * The counter takes a booking. Session 52.
     *
     * <h2>What this replaces</h2>
     * Darshan: <i>"suppose any customer calls the manager or comes to walk in, then the manager
     * should update it in the portal and the manager should select the stylist… we should
     * compulsorily have their data in our database."</i>
     *
     * <p>The only tool the desk had was {@code POST /api/v1/availability/walk-in}, which takes the
     * stylist's time off the calendar and records nothing else — no customer, no services, no
     * price, no invoice, no history. That endpoint stays, because "block this stylist for 20
     * minutes, no booking" is still a real thing a desk needs; this is the other, bigger case.
     *
     * <h2>Owner or manager of THIS salon</h2>
     * The salon comes from {@code requireSalonScope(caller)} — the token — so there is nothing in
     * the request to tamper with. Stylists are excluded for the same reason they are excluded from
     * walk-in blocks: taking bookings and holding customer contact details is the desk's job.
     *
     * <p>The booking comes back CONFIRMED with an invoice already raised, because the customer is
     * standing there and will pay at the counter. See {@code BookingService.createCounter}.
     */
    @Operation(
        summary = "Take a booking at the counter or over the phone",
        description = "Owner or manager of THIS salon. Creates (or matches) the salon's own customer record from the name and phone — a counter booking cannot be taken anonymously — then books through the same price resolution, slot validation and policy snapshot an app booking uses. Returns CONFIRMED with an invoice raised; there is no online payment order because the money is taken at the desk.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @PostMapping("/counter")
    public ResponseEntity<BookingResponse> counterBooking(
            @Valid @RequestBody CounterBookingRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        UUID salonId = requireSalonScope(caller);
        BookingResponse created = counter.take(salonId, req.items(),
                req.customerName(), req.customerPhone(), req.customerEmail(), req.notes(),
                caller.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
        summary = "The salon's day — schedule + summary",
        description = "Service blocks for one LOCAL calendar day (Asia/Kolkata), ordered by start time, plus counts and expected revenue. Optionally filtered to one stylist. Flattened to items on purpose: a booking of two services at two times with two stylists is two rows here. Cancelled bookings are included so a manager can see a slot freed up; the summary excludes them from revenue.")
    @GetMapping("/salon/day")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public SalonDayResponse salonDay(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID stylistId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        UUID salonId = requireSalonScope(caller);
        // "Today" means today in the salon's timezone, not the server's.
        LocalDate day = date != null ? date : LocalDate.now(BmpTimeZone.ZONE);
        return service.salonDay(salonId, day, stylistId);
    }

    /**
     * Session 44 — the history now filters.
     *
     * <p>{@code stylistId} answers "how has Ravi's month been?", and matches bookings a stylist
     * worked <b>any part of</b> (the stylist is on the item; one booking can span two chairs).
     *
     * <p>{@code search} matches the customer's name or the booking reference. It deliberately
     * does <b>not</b> match phone numbers — V006 refused an index on {@code customer_phone}
     * because phone lookup is an effective customer-enumeration tool and belongs in bmp-admin
     * where it is audited. Searching by name here grants the salon nothing it doesn't already
     * read off its own desk; searching by phone would.
     */
    @Operation(
        summary = "Booking history for your salon, with filters",
        description = "Newest first, by when the booking was MADE. `stylistId` narrows to one "
            + "stylist's work; `search` matches customer name or booking reference (not phone). "
            + "Everything is scoped to the caller's own salon, taken from the token — never a "
            + "parameter.")
    @GetMapping("/salon")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public PagedBookings salonList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID stylistId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.salonList(requireSalonScope(caller), status, stylistId, search, page, size);
    }

    /** One stylist's completed work over the window. Money in PAISE, like everything else. */
    public record StylistIncomeRow(UUID stylistId, long totalPaise, long completedCount) {}

    @Operation(
        summary = "Income per stylist",
        description = """
            The value of COMPLETED work per stylist, over a date range, for the caller's own salon.

            Precisely: the sum of each item's frozen snapshot price, for items whose own status is 'completed', by the time the service STARTED. Not billed, not collected, and not the stylist's pay — BMP does not process payments yet, so this is what was done, not what changed hands.

            Owner only. A manager can see the schedule; who earns what is the owner's business.""")
    @GetMapping("/salon/stylist-income")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public List<StylistIncomeRow> stylistIncome(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        UUID salonId = requireSalonScope(caller);
        if (to.isBefore(from)) {
            // A backwards range silently returns nothing, and "nobody earned anything" is a
            // uniquely bad thing to be confidently wrong about.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "RANGE_INVALID: 'to' must not be before 'from'.");
        }

        /*
         * Half-open [from 00:00, to+1 00:00) in the SALON's timezone, so "1st to 31st" includes
         * the whole of the 31st. Using `to` as the exclusive bound directly would silently drop
         * the last day — the classic off-by-one in every date-range report, and one nobody
         * notices because the number still looks reasonable.
         */
        var zone = com.bmp.common.time.BmpTimeZone.ZONE;
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        return items.sumCompletedByStylist(salonId, start, end).stream()
                .map(r -> new StylistIncomeRow(
                        (UUID) r[0],
                        r[1] == null ? 0L : ((Number) r[1]).longValue(),
                        r[2] == null ? 0L : ((Number) r[2]).longValue()))
                .sorted(java.util.Comparator.comparingLong(StylistIncomeRow::totalPaise).reversed())
                .toList();
    }

    @Operation(
        summary = "What's coming up at your salon",
        description = "Soonest first, one row per booking. NOT the history endpoint with a filter: "
            + "history orders by when the booking was MADE, so one created yesterday for next month "
            + "sorts above one created last week for tomorrow. Cancelled, completed and no-show "
            + "bookings are excluded — this is a work queue, and padding it with things that aren't "
            + "happening is how people stop trusting it.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @GetMapping("/salon/upcoming")
    public PagedBookings salonUpcoming(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.salonUpcoming(requireSalonScope(caller), page, size);
    }

    /**
     * The salon cancels. Session 37 — previously impossible.
     *
     * <h2>Why this was refused, and why that was wrong</h2>
     * {@code BookingStatus} allowed CANCELLED only from CUSTOMER and SYSTEM, on the reasoning
     * that "the salon cancels on the customer's behalf" isn't a modelled move. That holds for a
     * salon cancelling as a favour. It does not hold for a burst pipe or a stylist who quits on
     * Friday: the salon cannot serve the appointment, and refusing to model that doesn't stop it
     * — it just means the salon rings the customer while BMP's database still shows a live
     * booking, which then becomes a no-show against someone who did nothing wrong.
     *
     * <p><b>Always fee-free</b>, enforced in {@code CancellationTerms} before any policy band, so
     * no combination of a salon's own settings can charge a customer for the salon's problem.
     *
     * <p><b>A reason is required</b> and the customer is shown it. "Your appointment is
     * cancelled", from the business you booked with, with no explanation, is the message that
     * loses a customer permanently.
     */
    @Operation(
        summary = "Cancel a booking (salon)",
        description = "For when the salon genuinely can't serve the appointment. ALWAYS fee-free "
            + "regardless of timing or policy — a salon-caused cancellation charging the customer "
            + "would be indefensible. A reason of at least 5 characters is required and is sent to "
            + "the customer.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @PostMapping("/{bookingId}/salon-cancel")
    public BookingResponse salonCancel(
            @PathVariable UUID bookingId,
            @RequestBody CancelRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        UUID salonId = requireSalonScope(caller);
        requireSameSalon(bookingId, salonId);
        return service.cancel(bookingId, req, true, caller.userId());
    }

    /**
     * The salon moves a booking. Session 37.
     *
     * <p>Gated on the salon's own {@code salonCanRescheduleDirectly} policy, frozen per booking —
     * and that defaults to FALSE. A salon silently moving someone's Saturday morning is the kind
     * of thing discovered at the door. When it's off, the service's refusal names the
     * alternative (call the customer, or cancel — which never charges them) rather than just
     * saying no, because a salon told only "no" rings the customer anyway and BMP ends up with
     * no record of a change that definitely happened.
     *
     * <p>Does NOT consume the customer's reschedule allowance: they didn't ask for this change.
     */
    @Operation(
        summary = "Move a booking to a new time (salon)",
        description = "Requires the salon's policy to permit direct rescheduling (off by default). "
            + "A reason is required and the customer is notified with the old and new times. "
            + "Doesn't count against the customer's own reschedule limit.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @PostMapping("/{bookingId}/salon-reschedule")
    public BookingResponse salonReschedule(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        UUID salonId = requireSalonScope(caller);
        requireSameSalon(bookingId, salonId);
        return service.reschedule(bookingId, req, true, caller.userId());
    }

    /**
     * A counter customer's visits to this salon. Session 52.
     *
     * <p>Salon-scoped from the token like every other salon-actor read, so the path id alone
     * cannot reach another salon's customer. Managers get it as well as owners: "when was she
     * last in?" is a front-desk question, which is the same reasoning as the history tab.
     */
    @Operation(summary = "One counter customer's visits here",
               description = "Owner or manager of THIS salon. For customers in the salon's own book (V026) — people with no BMP account. Bookings made through the app use /salon/customer/{customerId} instead; the two ids are different things and are deliberately not interchangeable.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @GetMapping("/salon/counter-customer/{salonCustomerId}")
    public PagedBookings counterCustomerBookings(
            @PathVariable UUID salonCustomerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.salonCustomerBookings(requireSalonScope(caller), salonCustomerId, page, size);
    }

    /**
     * One customer's history <b>at this salon</b>. Session 36.
     *
     * <h2>Read the path carefully: {@code /salon/customer/{id}}, not {@code /customer/{id}}</h2>
     * The salon half comes from the caller's JWT and is not in the URL. That is what makes this
     * safe to expose: there is no request a manager can construct that returns a customer's
     * bookings at a different salon, because the only salon they can name is their own.
     *
     * <p>The service enforces the same pairing at the query level — see
     * {@code findBySalonIdAndCustomerIdOrderByCreatedAtDesc}, which has no single-argument
     * variant. Two independent places, because this is the boundary where a mistake is a
     * privacy incident rather than a bug.
     *
     * <h2>404 when they have never been here</h2>
     * Not an empty summary. An endpoint that returns 200-with-zeros for any well-formed UUID
     * lets a salon test arbitrary ids and learn which ones are real BMP customers.
     */
    @Operation(
        summary = "One customer's history at your salon",
        description = "Visits, spend, cancellations and their usual stylist — computed ONLY over "
            + "their bookings at your salon. Nothing here reflects anything they did anywhere else "
            + "on BMP. The phone number is masked; revealing it is a separate audited call. "
            + "404 if they have never booked with you.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @GetMapping("/salon/customer/{customerId}")
    public CustomerAtSalonResponse customerAtSalon(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.customerAtSalon(requireSalonScope(caller), customerId, page, size);
    }

    /**
     * Hand over the customer's real phone number — audited. Session 35.
     *
     * <h2>POST, not GET, and that is not pedantry</h2>
     * It reads data and returns it, which sounds like a GET. It is a POST because it <b>writes a
     * permanent record</b> every time it is called. A GET that mutates is a GET that browsers,
     * proxies, retry logic and link prefetchers will call again on their own — and every one of
     * those would be an unexplained "the salon looked up your number" entry in a customer's
     * booking history. It also needs a body: the reason is mandatory.
     *
     * <h2>Stylists are not on this list</h2>
     * {@code SALON_OWNER} and {@code MANAGER} only, the same pair as every other desk action.
     * A stylist's job is the appointment in front of them; ringing a late customer is the front
     * desk's. Narrower is the right default — widening it later is a one-word change, and every
     * role added here is another person who can enumerate the salon's customers.
     *
     * <p>{@code requireSameSalon} is what actually stops cross-salon reads: the role says what
     * KIND of person you are, the JWT's {@code salonId} says WHICH salon, and only the second
     * one keeps a manager out of another shop's bookings.
     */
    @Operation(
        summary = "Reveal the customer's phone number (audited)",
        description = "The day view shows a masked number. This returns the real one and writes a "
            + "CONTACT_REVEALED entry to the booking's event trail — which the CUSTOMER can also "
            + "read, in their own app. A reason is required; 'other' additionally requires a note. "
            + "Returns a null phone when BMP holds no number for that customer, which is different "
            + "from being refused.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @PostMapping("/{bookingId}/reveal-contact")
    public ContactRevealResponse revealContact(
            @PathVariable UUID bookingId,
            @Valid @RequestBody ContactRevealRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        UUID salonId = requireSalonScope(caller);
        requireSameSalon(bookingId, salonId);
        return service.revealCustomerContact(bookingId, caller.userId(), req);
    }

    @Operation(
        summary = "Mark the customer as arrived (CONFIRMED → ARRIVED)",
        description = "SALON-actor transition. NOTE: bookings currently sit in PENDING until the Razorpay webhook confirms them (Phase 3), so in practice nothing is in CONFIRMED yet and this returns 409. Deliberate — a manual confirm would create a second path into CONFIRMED that must not disagree with the webhook once it exists.")
    @PostMapping("/{bookingId}/arrive")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public BookingResponse arrive(@PathVariable UUID bookingId,
                                   @RequestBody(required = false) SalonActionRequest req,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return salonAction(bookingId, BookingStatus.ARRIVED, req, caller);
    }

    @Operation(summary = "Start the service (ARRIVED → IN_SERVICE)", description = "SALON-actor transition.")
    @PostMapping("/{bookingId}/start")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public BookingResponse start(@PathVariable UUID bookingId,
                                  @RequestBody(required = false) SalonActionRequest req,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return salonAction(bookingId, BookingStatus.IN_SERVICE, req, caller);
    }

    @Operation(summary = "Complete the booking (IN_SERVICE → COMPLETED)", description = "SALON-actor transition. Terminal; also closes the booking's service items.")
    @PostMapping("/{bookingId}/complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public BookingResponse complete(@PathVariable UUID bookingId,
                                     @RequestBody(required = false) SalonActionRequest req,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        return salonAction(bookingId, BookingStatus.COMPLETED, req, caller);
    }

    @Operation(summary = "Mark a no-show (CONFIRMED → NO_SHOW)", description = "SALON-actor transition, intended for after the grace period. Terminal.")
    @PostMapping("/{bookingId}/no-show")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public BookingResponse noShow(@PathVariable UUID bookingId,
                                   @RequestBody(required = false) SalonActionRequest req,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return salonAction(bookingId, BookingStatus.NO_SHOW, req, caller);
    }

    // ---- authorization helpers -------------------------------------------------------------

    private BookingResponse salonAction(UUID bookingId, BookingStatus target,
                                        SalonActionRequest req, AuthenticatedUser caller) {
        UUID salonId = requireSalonScope(caller);
        requireSameSalon(bookingId, salonId);
        return service.salonTransition(bookingId, target, caller.userId(), req == null ? null : req.note());
    }

    /**
     * The caller must be scoped to a salon. A SALON_OWNER who signed up but never created one
     * has a null claim — that's a 409, not a 403: they're allowed to be here, there's just
     * nothing to show yet, and saying so is more useful than "forbidden".
     */
    // ══ the stylist's own schedule ════════════════════════════════════════════════════════════
    //
    // Session 48.
    //
    // THE BUG THIS FIXES. The stylist dashboard called /bookings/salon/day, which is
    // hasAnyRole('SALON_OWNER','MANAGER'). Every real stylist got a 403 and the screen only ever
    // worked against mocks. The tempting one-line fix — add STYLIST to that annotation — would
    // have handed every stylist the customer's full name, masked phone (revealable via
    // /reveal-contact, also on that role list) and the price of every booking in the salon.
    //
    // THE SHAPE OF THE FIX. Separate endpoints, returning a type that has no field for any of
    // those, scoped to ids the caller cannot choose.

    /**
     * Resolve the CALLER to their own stylist id and salon.
     *
     * <h2>Why there is no {@code stylistId} parameter anywhere below</h2>
     * This is the entire access-control story for these three endpoints. A stylist's JWT carries
     * userId and role but no salonId, so the scope has to come from somewhere — and the one place
     * it must not come from is the request. A {@code ?stylistId=} parameter, however carefully
     * validated at first, is a permanent invitation to read a colleague's day, complete with the
     * names of their customers.
     *
     * <p>Instead bmp-salon answers "who is this login?" from the userId the token asserts. The
     * caller has no input.
     *
     * @throws ResponseStatusException 409 when they aren't on a team — a real state after
     *         self-registering, and "there's nothing to show yet" is more useful than "forbidden"
     */
    private com.bmp.booking.client.SalonAvailabilityClient.StylistIdentity stylistScope(
            AuthenticatedUser caller) {
        if (caller == null || caller.userId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NO_CALLER");
        }
        var me = salonClient.stylistByUser(caller.userId());
        if (me.salonId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "NOT_AT_A_SALON — a salon has to add you to their team before you have a "
                    + "schedule. You can send them a request from your profile.");
        }
        return me;
    }

    @Operation(
        summary = "My day (stylist)",
        description = "The caller's OWN appointments for one local day, plus counts and minutes "
            + "booked. Carries no customer id, phone, email or surname, and NO money of any "
            + "kind — the response type has no fields for them. The stylist is resolved from "
            + "your token; there is no id parameter to change.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/stylist/day")
    public StylistDayResponse stylistDay(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        var me = stylistScope(caller);
        // "Today" in the salon's timezone, not the server's — same rule as the manager's day.
        LocalDate day = date != null ? date : LocalDate.now(BmpTimeZone.ZONE);
        return service.stylistDay(me.salonId(), me.stylistId(), day);
    }

    @Operation(
        summary = "My upcoming appointments (stylist)",
        description = "Forward queue, soonest first. Cancelled and completed excluded — a queue "
            + "of things that aren't happening is a queue people stop trusting.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/stylist/upcoming")
    public PagedStylistBookings stylistUpcoming(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        var me = stylistScope(caller);
        return service.stylistUpcoming(me.salonId(), me.stylistId(), page, Math.min(size, 100));
    }

    @Operation(
        summary = "My past appointments (stylist)",
        description = "The work I've done at this salon, newest first. Same omissions: no "
            + "customer contact details, no amounts.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/stylist/history")
    public PagedStylistBookings stylistHistory(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @AuthenticationPrincipal AuthenticatedUser caller) {
        var me = stylistScope(caller);
        return service.stylistHistory(me.salonId(), me.stylistId(), page, Math.min(size, 100));
    }

    private UUID requireSalonScope(AuthenticatedUser caller) {
        if (caller == null || caller.salonId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NO_SALON_SCOPE — create a salon first");
        }
        return caller.salonId();
    }

    private void requireSameSalon(UUID bookingId, UUID callerSalonId) {
        if (!callerSalonId.equals(service.salonIdOf(bookingId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "BOOKING_BELONGS_TO_ANOTHER_SALON");
        }
    }

    /** Internal services (ROLE_SERVICE) bypass the self-check — they act on everyone's behalf. */
    private void requireSelfOrService(UUID customerId, AuthenticatedUser caller) {
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "NO_PRINCIPAL");
        }
        if (isService(caller) || caller.userId().equals(customerId)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_YOUR_BOOKING");
    }

    private void requireCustomerOrSalonStaff(UUID bookingId, AuthenticatedUser caller) {
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "NO_PRINCIPAL");
        }
        if (isService(caller)) return;
        if (caller.userId().equals(service.customerIdOf(bookingId))) return;
        if (caller.salonId() != null && caller.salonId().equals(service.salonIdOf(bookingId))) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_YOUR_BOOKING");
    }

    private boolean isService(AuthenticatedUser caller) {
        return "service".equalsIgnoreCase(caller.role());
    }
}
