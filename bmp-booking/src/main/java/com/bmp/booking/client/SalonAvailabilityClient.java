package com.bmp.booking.client;

import com.bmp.booking.client.dto.AvailabilitySlot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Session 10 — the booking-creation side of wiring in the availability algorithm.
 * BookingService calls this to (a) confirm a requested slot for a specific stylist is
 * still free at the moment of booking (protects against the customer having viewed slots
 * a while ago and someone else grabbing it first), and (b) resolve an actual stylist for
 * an "any_available" item that didn't come with one attached.
 *
 * <p>Note the resulting bidirectional coupling: bmp-booking calls bmp-salon here, and
 * bmp-salon's own AvailabilityService calls back into bmp-booking (its busy-windows
 * endpoint) to answer THIS call. This is intentional and not circular in the harmful
 * sense — different endpoints, no recursion — but worth knowing when reasoning about
 * this pair of services' runtime dependencies. See docs/AVAILABILITY_ALGORITHM.md.
 */
@FeignClient(name = "bmp-salon-service", configuration = com.bmp.booking.config.FeignInternalKeyConfig.class)
public interface SalonAvailabilityClient {

    /**
     * @param excludeBookingId Session 37. Null for an ordinary lookup. Set ONLY while that
     *                         booking is being rescheduled, so it doesn't count as busy against
     *                         itself — otherwise moving a 60-minute service from 11:00 to 11:30
     *                         is refused because 11:00–12:00 is occupied, by the booking being
     *                         moved. Every other booking stays visible.
     */
    @GetMapping("/api/v1/availability/slots")
    List<AvailabilitySlot> freeSlots(@RequestParam("salonId") UUID salonId,
                                       @RequestParam("stylistId") UUID stylistId,
                                       @RequestParam("date") LocalDate date,
                                       @RequestParam("durationMinutes") int durationMinutes,
                                       @RequestParam(value = "excludeBookingId", required = false) UUID excludeBookingId);

    @GetMapping("/api/v1/availability/slots/any")
    List<AvailabilitySlot> freeSlotsAnyStylist(@RequestParam("salonId") UUID salonId,
                                                 @RequestParam("date") LocalDate date,
                                                 @RequestParam("durationMinutes") int durationMinutes,
                                                 @RequestParam(value = "excludeBookingId", required = false) UUID excludeBookingId);

    /**
     * The salon's service menu — the authoritative name, price and duration.
     *
     * <p>Session 30. Added because {@code CreateBookingRequest.ItemRequest} carries
     * {@code nameSnapshot}, {@code pricePaise} and {@code durationMinutes} <b>supplied by the
     * client</b>, and bmp-booking used to write them straight to the database. A hand-rolled
     * POST could book a ₹4,500 service for ₹1, or a 120-minute service as 15 minutes — the
     * second being worse, because it also passes the availability check and then overruns three
     * other customers' appointments.
     *
     * <p>Mirrors the shape of bmp-salon's {@code SalonDtos.ServiceResponse}. A record with fewer
     * fields is fine — Jackson ignores the rest — but the names must match exactly.
     */
    record SalonService(UUID id, UUID salonId, String name, long pricePaise,
                        int durationMinutes, boolean requiresStylistAssignment) {}

    @GetMapping("/api/v1/salons/{salonId}/services")
    List<SalonService> listServices(@PathVariable("salonId") UUID salonId);

    /**
     * The salon's cancellation and commission terms.
     *
     * <p>Session 30. Two things on every booking used to be invented in bmp-booking:
     * commission (a hardcoded 12% for every salon on the platform) and {@code policy_snapshot}
     * (literally the string {@code "{}"}, on a column whose own migration comment says
     * "FROZEN copy of salon_policy, never changes").
     *
     * <p>Mirrors {@code SalonDtos.PolicyResponse}. Returns 404 when a salon has never set a
     * policy, which is normal for a new salon — the caller handles it.
     */
    record SalonPolicy(UUID id, UUID salonId, String template, int freeCancelHours,
                       int lateGraceMinutes, boolean requirePrepayment,
                       int slotGranularityMinutes, int commissionBps,
                       // ---- V010 (Session 37): what a cancellation costs, and whether the
                       // booking can still be moved. Frozen into policy_snapshot at creation;
                       // CancellationTerms reads the snapshot, never this client.
                       int lateCancelHours, int lateCancelFeeBps, int noNoticeFeeBps,
                       int rescheduleNoticeHours, int maxReschedulesPerBooking,
                       boolean salonCanRescheduleDirectly, boolean rescheduleKeepsOriginalClock) {}

    @GetMapping("/api/v1/salons/{salonId}/policy")
    SalonPolicy getPolicy(@PathVariable("salonId") UUID salonId);

    /**
     * The salon's own record — needed here for exactly one field: its name.
     *
     * <p>Session 34. "Your booking is confirmed" is a message from nobody. "Your booking at
     * Bounce Salon is confirmed" is one a customer can act on, and the name is the only thing
     * bmp-booking cannot derive from what it already holds.
     *
     * <p>Snapshotted onto the booking row (V006) rather than fetched per message, for the same
     * reason as price and policy: a salon that rebrands must not retroactively rewrite what a
     * customer was told six months ago.
     *
     * <p>Only {@code id} and {@code name} are declared — bmp-salon's {@code SalonResponse}
     * carries location, status and timestamps, and Jackson drops what isn't asked for. Adding
     * the rest would be inviting bmp-booking to start making decisions about a salon's state,
     * which is bmp-salon's job.
     *
     * <p>A failure here does not fail the booking. See {@code BookingService.resolveSalonName}:
     * the appointment is real whether or not the receipt has the shop's name on it.
     */
    /**
     * @param bookingNotifyEmail V011 (Session 40). Where the SALON wants booking alerts.
     * @param bookingNotifyPhone same, for SMS.
     *
     * <p>Fetched here, at booking time, and carried on {@code BookingCreated} — the same
     * emitter-carries-contact rule the customer's details follow. The dispatcher holds no
     * clients, so whoever emits an event is responsible for putting real addresses in it.
     *
     * <p>Both nullable. A salon that has set neither gets no alert and the dispatcher says so in
     * the log; it must never fail a booking, because the appointment is real either way.
     */
    record SalonSummary(UUID id, String name, String bookingNotifyEmail, String bookingNotifyPhone) {}

    @GetMapping("/api/v1/salons/{salonId}")
    SalonSummary getSalon(@PathVariable("salonId") UUID salonId);
}
