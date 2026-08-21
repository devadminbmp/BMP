package com.bmp.salon.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
                                      String area, String address, String about, String imageUrl,
                                      List<String> categories,
                                      String bookingNotifyEmail, String bookingNotifyPhone) {}

    /** Every V011 field is null-means-unchanged, same rule as PolicyRequest. */
    public record UpdateSalonRequest(String name, LatLng location, String status,
                                      String stylistAssignmentStrategy,
                                      String area, String address, String about, String imageUrl,
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
    public record SalonResponse(UUID id, String name, LatLng location, String status,
                                 String stylistAssignmentStrategy, Instant createdAt, Instant updatedAt,
                                 String bookingNotifyEmail, String bookingNotifyPhone) {}

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
                                       String area, String address, String about, String imageUrl,
                                       java.math.BigDecimal rating, int reviewCount,
                                       List<String> categories,
                                       Long startingPricePaise, String openHours,
                                       List<ServiceResponse> services,
                                       List<PublicStylistResponse> stylists) {}

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
                                 Boolean salonCanRescheduleDirectly, Boolean rescheduleKeepsOriginalClock) {}

    public record PolicyResponse(UUID id, UUID salonId, String template, int freeCancelHours,
                                  int lateGraceMinutes, boolean requirePrepayment, int slotGranularityMinutes,
                                  int commissionBps,
                                  // ---- V010 ----
                                  int lateCancelHours, int lateCancelFeeBps, int noNoticeFeeBps,
                                  int rescheduleNoticeHours, int maxReschedulesPerBooking,
                                  boolean salonCanRescheduleDirectly, boolean rescheduleKeepsOriginalClock) {}

    public record HourEntry(int dayOfWeek, @NotBlank String openTime, @NotBlank String closeTime) {}

    public record HoursRequest(@NotEmpty List<@Valid HourEntry> hours) {}

    public record HoursResponse(UUID salonId, List<HourEntry> hours) {}

    /** @param category V011 — groups the menu on the salon page. Null means "Other". */
    public record ServiceRequest(@NotBlank String name, @NotNull long pricePaise,
                                  @NotNull int durationMinutes, boolean requiresStylistAssignment,
                                  String category) {}

    public record ServiceResponse(UUID id, UUID salonId, String name, long pricePaise,
                                   int durationMinutes, boolean requiresStylistAssignment,
                                   String category) {}

    public record ErrorResponse(String error, String message) {}
}
