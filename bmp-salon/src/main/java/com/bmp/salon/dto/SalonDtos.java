package com.bmp.salon.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BMP-23 DTOs — salon_schema.salon / salon_policy / salon_hours / salon_service. */
public final class SalonDtos {
    private SalonDtos() {}

    public record LatLng(@NotNull double lat, @NotNull double lng) {}

    /**
     * @param address    V011. Collected by SalonSignupSheet since Session 15 and DISCARDED,
     *                   because this record had nowhere to put it. The owner typed into a field
     *                   that went nowhere (PENDING_WORK F2). Now it lands.
     * @param categories V011. What the salon does — "hair", "skin", "nails". Lower-cased and
     *                   de-duplicated by the service.
     */
    public record CreateSalonRequest(@NotBlank String name, @NotNull LatLng location,
                                      String stylistAssignmentStrategy,
                                      String area, String pincode, String address, String about, String imageUrl,
                                      List<String> categories,
                                      String bookingNotifyEmail, String bookingNotifyPhone) {}

    /** Every V011 field is null-means-unchanged, same rule as PolicyRequest. */
    public record UpdateSalonRequest(String name, LatLng location, String status,
                                      String stylistAssignmentStrategy,
                                      String area, String pincode, String address, String about, String imageUrl,
                                      /* V015 — upload key for the cover image; null for a link. */
                                      String imageStorageKey,
                                      List<String> categories,
                                      String bookingNotifyEmail, String bookingNotifyPhone) {}

    /**
     * The administrative view of a salon: status, strategy, timestamps. Served for ANY salon
     * regardless of approval, because bmp-booking and the console both depend on that. The
     * customer-facing shape is {@link SalonDetailResponse}.
     *
     * @param bookingNotifyEmail V011 (Session 40). Read by bmp-booking at booking time and put
     *                           on {@code BookingCreated}, so the salon finally gets told when
     *                           somebody books — see {@code SalonAvailabilityClient.SalonSummary}.
     *
     *        <p>These are on the internal/administrative response rather than the public one
     *        deliberately: a salon's alert inbox is not something a browsing customer should be
     *        able to read off the salon page.
     */
    /**
     * The salon as its OWNER sees it. Session 48 — it grew, and it needed to.
     *
     * <h2>What the owner could not see</h2>
     * This carried id, name, location, status and the two notify fields. The dashboard's "Your
     * salon" card therefore had nothing to show and fell back to rendering the raw UUID and the
     * signed-in user's phone number — so the owner saw a 36-character id where the admin console
     * shows BMPS001, and "Owner: +919113639755" instead of their own name. Everything they typed
     * during signup — area, PIN code, address, what kind of salon it is — was stored and then
     * shown back to them nowhere.
     *
     * <p>Adding fields to an owner-facing read is safe in a way that adding them to a customer
     * response is not: the caller is already authorised as this salon's owner or manager, so
     * there is no new exposure. (Contrast SalonDetailResponse, where Session 44 nearly leaked
     * internal storage keys to the public.)
     */
    public record SalonResponse(UUID id, String name, LatLng location, String status,
                                 String stylistAssignmentStrategy, Instant createdAt, Instant updatedAt,
                                 String bookingNotifyEmail, String bookingNotifyPhone,
                                 /** BMPS001 — what support asks for. Null for pre-V017 salons. */
                                 String reference,
                                 String area, String pincode, String address, String about,
                                 String imageUrl,
                                 java.util.List<String> categories,
                                 /** When the owner published. Null = never gone live. V018. */
                                 Instant wentLiveAt) {}

    /**
     * A row in the discovery list. V011 (Session 40) turned this from three fields into
     * something a customer can actually choose from.
     *
     * <h2>Why it grew</h2>
     * The frontend's {@code SalonSchema} has asked for {@code area}, {@code rating},
     * {@code reviewCount}, {@code categories} and {@code startingPricePaise} since Session 12,
     * and this record sent {@code (id, name, distanceKm)}. The Zod parse threw, so <b>the salon
     * list has never rendered against a real backend</b> — invisible because mocks default ON and
     * were written from the client's assumptions rather than this contract.
     *
     * @param rating              NULL means "no reviews yet" — <b>not</b> zero. A new salon shown
     *                            as "0.0 ★" reads as terrible rather than new, and that cost
     *                            falls on the salon least able to absorb it. Render as "New".
     * @param startingPricePaise  DERIVED — the cheapest service on the menu, not a stored column.
     *                            Storing it would be a second copy of a price that goes stale the
     *                            moment an owner edits a service. Null when the menu is empty,
     *                            which is a real state for a salon mid-setup.
     *
     * <p>Deliberately absent: {@code imageHue} and {@code topRated}, which the client used to
     * expect. A hue is not a fact about a business, and "top rated" is a threshold over
     * {@code rating} that belongs wherever the threshold is decided. Both are derived in the app.
     */
    /*
     * Session 48 — `pincode` is deliberately ABSENT here.
     *
     * This is the search-results payload, returned for every salon in a list. The card shows a
     * name, a distance and an area; a PIN code has nowhere to render and would be dead weight on
     * the one response whose size scales with the number of salons.
     *
     * Searching BY pincode still works — SalonService matches it server-side (see near()); the
     * client never needs the value back to have searched on it. Adding a field to a list response
     * because a detail response has it is how list payloads quietly double.
     */
    public record NearbySalonResponse(UUID id, String name, double distanceKm,
                                       String area, String imageUrl,
                                       java.math.BigDecimal rating, int reviewCount,
                                       List<String> categories, Long startingPricePaise) {}

    /**
     * Everything the salon page needs, in ONE call. V011 (Session 40).
     *
     * <h2>Why one call rather than three</h2>
     * This is the conversion funnel: a customer who reaches this screen is deciding whether to
     * book. Three sequential round-trips on a Bengaluru 4G connection is three chances to show a
     * spinner and two extra chances to fail, on the screen where dropping the customer costs the
     * most. The services and stylists are already loaded by the same transaction.
     *
     * @param openHours a DISPLAY string composed from {@code salon_hours} — "Mon–Sat, 10:00–20:00".
     *                  The structured per-weekday rows stay authoritative for the availability
     *                  algorithm; this is prose for a human, and prose is a presentation concern.
     *                  Null when the salon has never set hours, which the UI states plainly rather
     *                  than guessing "9 to 5".
     */
    public record SalonDetailResponse(UUID id, String name, LatLng location, String status,
                                       String area, String pincode, String address, String about, String imageUrl,
                                       java.math.BigDecimal rating, int reviewCount,
                                       List<String> categories,
                                       Long startingPricePaise, String openHours,
                                       /**
                                        * V022 (Session 49) — how many days ahead this salon takes
                                        * bookings.
                                        *
                                        * <p>Sent to the CUSTOMER so the date picker only offers
                                        * days that can actually produce slots. Without it the app
                                        * would render its usual 30-day strip against a salon with
                                        * a 3-day window, and 27 of those dates would return an
                                        * empty list — which reads as "this salon is fully booked
                                        * for a month", not as "they don't open their diary that
                                        * far ahead".
                                        */
                                       int bookingHorizonDays,
                                       List<ServiceResponse> services,
                                       List<PublicStylistResponse> stylists,
                                       /**
                                        * V014 (Session 44) — the gallery a customer browses
                                        * before booking. Empty for a salon that hasn't added
                                        * any; the UI falls back to the single card image.
                                        *
                                        * Included in the SAME call as services and stylists for
                                        * the reason stated above: this screen is the conversion
                                        * funnel and every extra round trip is another spinner
                                        * and another chance to lose the customer.
                                        */
                                       List<SalonPhotoResponse> photos,
                                       /**
                                        * A closure covering right now, or the next one within a
                                        * week. V019 / Session 64.
                                        *
                                        * <h2>Why this is on the detail response at all</h2>
                                        * Closures have blocked BOOKING since Session 48 —
                                        * AvailabilityService subtracts every closure window from
                                        * the slot search, correctly. But nothing told the CUSTOMER.
                                        * A salon shut for Diwali looked exactly like an open salon
                                        * with a full diary: the page rendered normally, the date
                                        * strip offered the day, and tapping it returned an empty
                                        * list.
                                        *
                                        * "No slots" and "closed" are different facts and a person
                                        * acts differently on each. The first means try another
                                        * time; the second means try another day, or another salon.
                                        * Making them look identical wastes the customer's time and
                                        * costs the salon a booking it would happily have taken on
                                        * Thursday.
                                        *
                                        * Null when the salon is open now and has nothing scheduled
                                        * in the next seven days — the common case, and cheaper to
                                        * omit than to send a "notClosed" object every request.
                                        */
                                       ClosureNotice closureNotice) {}

    /**
     * What a customer is told about a salon being shut.
     *
     * @param closedNow  true if the closure covers this moment. Drives whether the page says
     *                   "Closed today" or "Closed 12–14 November" — present tense and future tense
     *                   need different words, and getting that wrong reads as a broken template.
     * @param reason     the salon's own words ("Closed for Diwali"), or null. Optional on purpose:
     *                   a salon is entitled to shut without explaining, and forcing a reason just
     *                   produces the word "closed" in a field that already means closed.
     */
    public record ClosureNotice(boolean closedNow, java.time.Instant startsAt,
                                 java.time.Instant endsAt, String reason) {}

    /**
     * A stylist as a CUSTOMER sees them. V011.
     *
     * <h2>Why this isn't {@code StylistSalonResponse}</h2>
     * That record is the desk's view and carries {@code stylistUserId} — the id of the person's
     * login account. Handing that to the public would leak the internal user graph to anyone who
     * opens a salon page, for no benefit: a customer picks a stylist by name and reputation, and
     * has no use for their account id.
     *
     * <p>{@code rating} is the stylist's rating <b>at this salon</b>
     * ({@code stylist_salon.salon_rating}), not their cross-salon lifetime figure. A colourist
     * who was excellent somewhere else is not evidence about this shop.
     */
    public record PublicStylistResponse(UUID id, String name, String speciality,
                                         java.math.BigDecimal rating, int reviewCount,
                                         boolean isAvailableToday) {}

    /**
     * @param commissionBps platform commission in basis points (1200 = 12.00%). Session 30.
     *
     *        <p><b>Nullable on the way IN, deliberately.</b> A salon owner editing their
     *        cancellation window must not be able to set their own commission rate by adding a
     *        field to the request — so when this is null the existing value is kept, and it is
     *        only ever changed by staff or an internal caller. Owners can SEE it (it's their
     *        money too); they cannot choose it.
     */
    /**
     * @param commissionBps null means "leave it alone" — see {@code upsertPolicy}.
     *
     * <p>The V010 fields are all {@code Integer}/{@code Boolean} rather than primitives, for the
     * same reason: <b>null means "leave unchanged"</b>. An owner editing their cancellation
     * window through a client that predates these fields must not silently reset their whole
     * fee policy to zero. Primitives would default to 0/false on deserialisation and do exactly
     * that, and it is the kind of bug that is only noticed at the end of a month.
     */
    public record PolicyRequest(@NotBlank String template, int freeCancelHours, int lateGraceMinutes,
                                 boolean requirePrepayment, int slotGranularityMinutes,
                                 Integer commissionBps,
                                 // ---- V010 ----
                                 Integer lateCancelHours, Integer lateCancelFeeBps, Integer noNoticeFeeBps,
                                 Integer rescheduleNoticeHours, Integer maxReschedulesPerBooking,
                                 Boolean salonCanRescheduleDirectly, Boolean rescheduleKeepsOriginalClock,
                                 // ---- V022: the booking window (Session 49) ----
                                 /**
                                  * How many days ahead customers may book. Null = leave unchanged,
                                  * the same "null means don't touch" rule the V010 fields use —
                                  * a client that omits the field must not reset it.
                                  */
                                 Integer bookingHorizonDays,
                                 /** Refuse slots sooner than this from now. 0 = no wait. */
                                 Integer minNoticeMinutes) {}

    public record PolicyResponse(UUID id, UUID salonId, String template, int freeCancelHours,
                                  int lateGraceMinutes, boolean requirePrepayment, int slotGranularityMinutes,
                                  int commissionBps,
                                  // ---- V010 ----
                                  int lateCancelHours, int lateCancelFeeBps, int noNoticeFeeBps,
                                  int rescheduleNoticeHours, int maxReschedulesPerBooking,
                                  boolean salonCanRescheduleDirectly, boolean rescheduleKeepsOriginalClock,
                                  // ---- V022 ----
                                  int bookingHorizonDays, int minNoticeMinutes) {}

    public record HourEntry(int dayOfWeek, @NotBlank String openTime, @NotBlank String closeTime) {}

    public record HoursRequest(@NotEmpty List<@Valid HourEntry> hours) {}

    public record HoursResponse(UUID salonId, List<HourEntry> hours) {}

    /** @param category V011 — groups the menu on the salon page. Null means "Other". */
    /** @param description V013 — what's included. @param imageUrl V013 — a URL, not an upload. */
    public record ServiceRequest(@NotBlank String name, @NotNull long pricePaise,
                                  @NotNull int durationMinutes, boolean requiresStylistAssignment,
                                  String category, String description, String imageUrl,
                                  /* V015 — set when imageUrl came from this salon's upload
                                     endpoint, absent for a pasted link. Re-validated server-side
                                     against the salon; see SalonService.requireOwnKeyOrNull. */
                                  String imageStorageKey) {}

    /**
     * Edit an existing service. V012 (Session 44).
     *
     * <p><b>Every field is null-means-unchanged</b>, the same contract as {@code UpdateSalonRequest}
     * — so a client that doesn't know about a field cannot blank it. That matters more here than
     * usual: a form that helpfully sent {@code category: null} would silently un-file the service
     * from the customer menu.
     *
     * <p>Boxed types, not primitives, precisely so "absent" and "zero" stay different questions.
     * {@code pricePaise: 0} is a free service, which is a real thing a salon might offer;
     * {@code pricePaise: null} means don't touch the price.
     *
     * <p>Editing is safe against history: {@code booking_service_item} freezes name, price and
     * duration when the booking is made. Nothing here can reach a booking that already exists.
     */
    public record UpdateServiceRequest(String name, Long pricePaise, Integer durationMinutes,
                                        Boolean requiresStylistAssignment, String category,
                                        String description, String imageUrl,
                                        /* V015. Travels WITH imageUrl — changing the picture
                                           replaces both, and the old object is deleted. */
                                        String imageStorageKey) {}

    /** @param archivedAt V012 — non-null means retired: off the customer menu, unbookable, but
     *                    still resolvable by past bookings, combos and stylist skill lists. */
    public record ServiceResponse(UUID id, UUID salonId, String name, long pricePaise,
                                   int durationMinutes, boolean requiresStylistAssignment,
                                   String category, java.time.Instant archivedAt,
                                   String description, String imageUrl) {}

    // ── V014 (Session 44): the salon gallery ─────────────────────────────────────────────────

    /** @param url must be http/https — see SalonService.requireHttpUrlOrNull for why. */
    /**
     * Add or edit a gallery photo.
     *
     * @param url        where the image lives. Required — a photo with no URL is not a photo.
     * @param storageKey V015 (Session 44). Present when {@code url} came from THIS salon's
     *                   upload endpoint, absent when the owner pasted a link to an image they
     *                   host elsewhere. Both are fully supported.
     *                   <p><b>Never trusted as given.</b> The key embeds a salon id, and
     *                   {@code SalonService} re-checks that it matches the salon being written —
     *                   otherwise a client could upload to its own salon and attach the result to
     *                   someone else's, which is the "authorise the path, then trust the body"
     *                   hole this codebase keeps re-finding.
     */
    public record SalonPhotoRequest(@NotBlank String url, String storageKey, String caption,
                                    Integer sortOrder) {}

    public record SalonPhotoResponse(UUID id, UUID salonId, String url, String caption,
                                      int sortOrder, Instant createdAt) {}

    /**
     * The owner's own approval status. Session 46.
     *
     * <p>Until now an owner was never told this: the login response carried no status and the
     * dashboard rendered a full working desk whether the salon was pending, rejected or approved.
     * A rejected owner would build a service menu and wait for bookings that could never arrive.
     *
     * @param salonStatus     the salon's own column — pending | approved | active | rejected |
     *                        suspended. Authoritative for "can customers see me", and readable
     *                        even when bmp-admin is down.
     * @param reviewStatus    the moderation row's status. Null when never submitted, or when
     *                        bmp-admin couldn't be reached.
     * @param decisionNote    the moderator's reason. <b>Shown to the owner verbatim on
     *                        rejection</b> — it is the only thing telling them what to fix.
     * @param submissionCount which attempt this is. 0 when never submitted.
     * @param canResubmit     true only when rejected. An approved salon has nothing to resubmit;
     *                        a suspended one was stopped deliberately and must not be able to
     *                        route around that by rejoining the queue.
     */
    /**
     * @param isLive    the salon is published and bookable right now
     * @param canGoLive approved, not yet live — the Go Live button is the owner's next action.
     *                  Computed here rather than left to the client: three UIs would each derive
     *                  it from `salonStatus` and one of them would eventually disagree.
     */
    public record SalonApprovalResponse(
        String salonStatus, String reviewStatus, String decisionNote,
        Instant submittedAt, Instant decidedAt, int submissionCount, boolean canResubmit,
        boolean isLive, boolean canGoLive) {}

    /** @param note what the owner says they fixed. Optional, but it speeds up the re-review. */
    public record ResubmitRequest(String note) {}

    // ── V019 (Session 48): closures ───────────────────────────────────────────────────────────

    /**
     * @param startsAt inclusive, @param endsAt exclusive — a booking exactly at endsAt is fine.
     * @param reason   optional. Shown to customers whose booking falls in the window; a salon is
     *                 entitled to shut without explaining, and requiring a reason just produces
     *                 the word "closed".
     */
    public record CreateClosureRequest(
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Size(max = 200) String reason) {}

    /**
     * @param affectedBookings how many live bookings fall inside this window. The number that
     *                         matters — a closure with 0 is done, one with 4 is four phone calls.
     *                         -1 when bmp-booking could not be reached; see SalonController.
     */
    public record ClosureResponse(
        UUID id, Instant startsAt, Instant endsAt, String reason,
        boolean active, Instant createdAt, int affectedBookings) {}

    public record ErrorResponse(String error, String message) {}
}
