package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.api.AvailabilityApi;
import com.bmp.salon.services.AvailabilityService;
import com.bmp.salon.dto.AvailabilityDtos.BlockWalkInRequest;
import com.bmp.salon.dto.AvailabilityDtos.SalonDayAvailability;
import com.bmp.salon.dto.AvailabilityDtos.SlotResponse;
import com.bmp.salon.dto.AvailabilityDtos.StylistDayAvailability;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Session 8 — the first real consumer-facing surface for the availability algorithm.
 * See AvailabilityService for the actual interval math and every design decision behind it.
 *
 * <h2>Session 29 authorization pass</h2>
 * The two slot READS are genuinely public, and that is the product model: a guest browses,
 * picks a time, and only then meets the login gate at "Book". Requiring a token to see what's
 * free would move the login wall earlier — exactly where bookings get abandoned. They leak
 * nothing beyond "this stylist is free at 4pm", which is the same thing a phone call reveals.
 *
 * <p>The WRITE is not public. See {@code blockWalkIn}.
 */
@Tag(name = "Availability", description = "Free-slot computation and the front-desk walk-in-block quick-add. Combines stylist_availability + walk_in_block + salon_hours + salon_policy (all local) with a live call to bmp-booking-service for already-committed bookings/checkout holds.")
@RestController
@RequestMapping("/api/v1/availability")
public class AvailabilityController {

    private final AvailabilityApi availability;
    /** Session 52 — the batched day view resolves stylist names in the same response. */
    private final com.bmp.salon.services.AvailabilityService availabilityService;
    private final com.bmp.salon.repositories.StylistRepository stylistRepo;
    private final com.bmp.salon.repositories.StylistSalonRepository stylistSalonRepo;

    public AvailabilityController(AvailabilityApi availability,
                                   com.bmp.salon.services.AvailabilityService availabilityService,
                                   com.bmp.salon.repositories.StylistRepository stylistRepo,
                                   com.bmp.salon.repositories.StylistSalonRepository stylistSalonRepo) {
        this.availability = availability;
        this.availabilityService = availabilityService;
        this.stylistRepo = stylistRepo;
        this.stylistSalonRepo = stylistSalonRepo;
    }

    /**
     * Everyone who can take this booking, with their free times — ONE call. Session 52.
     *
     * <h2>Why this exists</h2>
     * Darshan: *"the ui for stylist available is a bit laggy… when I choose the date and time I
     * need to get which stylists are available — make it fast."*
     *
     * <p>The picker used to call {@code /slots} once per stylist. Each of those was a separate
     * HTTP request from the app AND a separate cross-service call inside the backend, so a
     * six-stylist salon cost the phone six requests and the backend thirty-odd queries to answer
     * one question. Now: one request in, one batched busy-windows call inside, one payload out
     * carrying names and ratings so nothing has to be joined on the device.
     *
     * <h2>Public, like the other two reads</h2>
     * A guest picking a time before meeting the login gate needs exactly this. It reveals no more
     * than {@code /slots/any} already does — who works here and when they are free — which is
     * what a phone call to the salon reveals too. No customer data is anywhere near it.
     */
    @Operation(summary = "Who is free on this day, with their slots — one call",
               description = "Every active stylist at the salon with their free start times for the given date and duration. Stylists with no room appear with busy=true and an empty slot list, so the picker can show them greyed rather than silently dropping them.")
    @GetMapping("/salon-day")
    public SalonDayAvailability salonDay(
            @RequestParam UUID salonId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam int durationMinutes,
            @RequestParam(required = false) UUID excludeBookingId) {

        java.util.Map<UUID, List<com.bmp.salon.api.AvailabilityApi.Slot>> byStylist =
                availabilityService.salonDayAvailability(salonId, date, durationMinutes, excludeBookingId);

        // Names and per-salon ratings, resolved in two reads rather than one per stylist.
        java.util.Map<UUID, com.bmp.salon.entities.Stylist> profiles =
                stylistRepo.findAllById(byStylist.keySet()).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.bmp.salon.entities.Stylist::getId, s -> s));
        java.util.Map<UUID, com.bmp.salon.entities.StylistSalon> links =
                stylistSalonRepo.findBySalonIdAndStatus(salonId, "active").stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.bmp.salon.entities.StylistSalon::getStylistId, l -> l,
                                (a, b) -> a));

        List<StylistDayAvailability> out = new java.util.ArrayList<>();
        for (var entry : byStylist.entrySet()) {
            UUID stylistId = entry.getKey();
            var profile = profiles.get(stylistId);
            // A link without a profile row shouldn't happen, but skipping is better than a card
            // labelled "null" — and the availability answer is unaffected either way.
            if (profile == null) continue;
            var link = links.get(stylistId);

            List<SlotResponse> slots = entry.getValue().stream()
                    .map(s -> new SlotResponse(s.start(), s.end(), s.stylistId()))
                    .toList();

            out.add(new StylistDayAvailability(
                    stylistId,
                    profile.getName(),
                    profile.getSpeciality(),
                    // Prefer THIS salon's rating: a stylist's platform-wide average says less to
                    // a customer choosing between the people standing in this one room.
                    link != null && link.getSalonRating() != null
                            ? link.getSalonRating() : profile.getOverallRating(),
                    link != null ? link.getSalonReviewCount() : profile.getTotalReviews(),
                    slots.isEmpty(),
                    slots));
        }

        // Free people first, then by rating — the picker's default order is the useful one.
        out.sort(java.util.Comparator
                .comparing(StylistDayAvailability::busy)
                .thenComparing(a -> a.rating() == null ? java.math.BigDecimal.ZERO : a.rating(),
                               java.util.Comparator.reverseOrder()));

        return new SalonDayAvailability(date, durationMinutes, out);
    }

    /**
     * Slots, plus WHY there are none. Session 68.
     *
     * @param reason  machine-readable, so a client can branch (e.g. offer "set your hours").
     * @param message a sentence for the reader. Empty when there are slots.
     */
    public record SlotsResponse(List<SlotResponse> slots, String reason, String message) {}

    /**
     * Session 68 — this endpoint used to return a bare list, and its own description admitted the
     * problem: <i>"Empty list means fully booked, on leave, or the salon is closed that day of
     * week."</i> Three causes, one indistinguishable answer — and in fact five.
     *
     * <p>Darshan hit it on a new salon: a stylist with an empty diary was reported "fully booked"
     * because the salon had no opening hours. Every new salon on BMP hits this on day one.
     *
     * <h2>{@code forOwner} decides how much truth comes back</h2>
     * The flag is NOT taken from a request parameter — that would let any customer ask for the
     * owner's version by flipping a boolean. It is derived from the authenticated principal:
     * staff of THIS salon get the diagnosis, everybody else gets "no times available".
     *
     * <p>That matters because two of the reasons are disclosures a salon would not choose to
     * make: that they have never configured their rota, and that a named stylist is suspended.
     */
    @Operation(
        summary = "Free slots for one stylist",
        description = "Grid-aligned start times with a contiguous run of durationMinutes free. When the list is empty, `reason` and `message` say why — salon staff get the actual cause and where to fix it; customers get a neutral sentence.")
    @GetMapping("/slots")
    public SlotsResponse freeSlots(@RequestParam UUID salonId, @RequestParam UUID stylistId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                          @RequestParam int durationMinutes,
                                          // Session 37. Only ever set while a booking is being
                                          // moved, so it doesn't block itself. See AvailabilityApi.
                                          @RequestParam(required = false) UUID excludeBookingId,
                                          @AuthenticationPrincipal AuthenticatedUser caller) {
        var result = availability.freeSlotsExplained(salonId, stylistId, date, durationMinutes, excludeBookingId);
        return toSlotsResponse(result, salonId, stylistId, caller);
    }

    /**
     * Staff of THIS salon, and nobody else.
     *
     * <p>A null principal is a guest on the public salon page — the commonest caller of all, and
     * emphatically not an owner. Anonymous access to this endpoint is deliberate (a customer must
     * be able to see times before signing in), which is exactly why the check cannot be "are you
     * authenticated" and has to be "are you staff HERE".
     */
    private boolean isSalonStaff(AuthenticatedUser caller, UUID salonId) {
        return caller != null
                && caller.salonId() != null
                && caller.salonId().equals(salonId);
    }

    private SlotsResponse toSlotsResponse(AvailabilityService.SlotResult result, UUID salonId,
                                           UUID stylistId, AuthenticatedUser caller) {
        List<SlotResponse> slots = result.slots().stream()
                .map(s -> new SlotResponse(s.start(), s.end(), s.stylistId()))
                .toList();
        if (result.reason() == AvailabilityService.NoSlotReason.OK) {
            return new SlotsResponse(slots, "OK", "");
        }
        // The stylist's name only when we have one and the reader is staff — the owner's messages
        // read "Shivam has no working hours set", which is worth the lookup; a customer never
        // sees a message that mentions a person.
        boolean owner = isSalonStaff(caller, salonId);
        String name = owner && stylistId != null ? stylistNameOrNull(stylistId) : null;
        return new SlotsResponse(slots, result.reason().name(),
                AvailabilityService.explain(result.reason(), owner, name));
    }

    @Operation(
        summary = "\"Any available\" free slots across every active stylist at the salon",
        description = "The customer-facing \"any stylist\" path. When empty, `reason` and `message` explain why — salon-level causes (no opening hours, closed today) are named for staff.")
    @GetMapping("/slots/any")
    public SlotsResponse freeSlotsAnyStylist(@RequestParam UUID salonId,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                    @RequestParam int durationMinutes,
                                                    @RequestParam(required = false) UUID excludeBookingId,
                                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        List<SlotResponse> slots = availability
                .freeSlotsAnyStylist(salonId, date, durationMinutes, excludeBookingId).stream()
                .map(s -> new SlotResponse(s.start(), s.end(), s.stylistId()))
                .toList();
        if (!slots.isEmpty()) return new SlotsResponse(slots, "OK", "");

        /*
         * Session 68 — the salon-level reason, which is the one that matters here.
         *
         * "Anyone available" is the union across the whole team, so if it is empty the useful
         * question is almost always about the SALON (no hours, closed today, date out of range)
         * rather than about any one stylist. When the salon is fine and the union is still empty,
         * the honest answer is that nobody has a gap — which is what FULLY_BOOKED says.
         *
         * Reusing salonLevelReason rather than re-deriving it: one copy of that ordering, shared
         * with freeSlotsExplained.
         */
        var reason = availability.salonLevelReason(salonId, date);
        if (reason == AvailabilityService.NoSlotReason.OK) {
            reason = AvailabilityService.NoSlotReason.FULLY_BOOKED;
        }
        boolean owner = isSalonStaff(caller, salonId);
        return new SlotsResponse(List.of(), reason.name(),
                AvailabilityService.explain(reason, owner, owner ? "Nobody" : null));
    }

    /**
     * The stylist's name for an owner-facing message, or null.
     *
     * <p>Null-tolerant on purpose: {@code explain} falls back to "This stylist", so a missing row
     * degrades the sentence rather than failing the request. A booking screen that 500s because a
     * name lookup missed would be a worse bug than the one this whole change fixes.
     */
    private String stylistNameOrNull(UUID stylistId) {
        try {
            return stylistRepo.findById(stylistId).map(s -> s.getName()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private List<SlotResponse> unusedLegacyMap(List<AvailabilityService.Slot> in) {
        return in.stream()
                .map(s -> new SlotResponse(s.start(), s.end(), s.stylistId()))
                .toList();
    }

    /**
     * OWNER or MANAGER of this salon. NOT a stylist, and certainly not the public.
     *
     * <p>This writes a block onto a stylist's calendar, which removes that time from what
     * customers can book. Until Session 29 it required no credential at all: anyone could have
     * blocked out every stylist at every salon for the next month and quietly taken the
     * platform's supply offline. No error, no alert — the slots would simply stop appearing.
     *
     * <p>Stylists are excluded deliberately. Blocking their own time is a legitimate need and
     * they have it — through the availability endpoints on their own dashboard, which record it
     * as time off. A walk-in is a front-desk action: it represents a customer standing at the
     * counter, and the desk owns the diary.
     */
    @Operation(summary = "Block a walk-in (the <5-second front-desk quick-add)", description = "Owner or manager OF THIS SALON. One overlap check against existing bookings/holds/breaks/leave/other walk-ins, then a single insert. Returns 409 if the window isn't actually free.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#req.salonId())")
    @PostMapping("/walk-in")
    public ResponseEntity<Void> blockWalkIn(@Valid @RequestBody BlockWalkInRequest req) {
        availability.blockWalkIn(req.salonId(), req.stylistId(), req.date(), req.start(), req.durationMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
