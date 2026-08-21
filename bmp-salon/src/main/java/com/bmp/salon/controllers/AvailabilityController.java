package com.bmp.salon.controllers;

import com.bmp.salon.api.AvailabilityApi;
import com.bmp.salon.dto.AvailabilityDtos.BlockWalkInRequest;
import com.bmp.salon.dto.AvailabilityDtos.SlotResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public AvailabilityController(AvailabilityApi availability) {
        this.availability = availability;
    }

    @Operation(summary = "Free slots for one stylist", description = "Grid-aligned start times (salon_policy.slot_granularity_minutes) with a contiguous run of durationMinutes free. Empty list means fully booked, on leave, or the salon is closed that day of week.")
    @GetMapping("/slots")
    public List<SlotResponse> freeSlots(@RequestParam UUID salonId, @RequestParam UUID stylistId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                          @RequestParam int durationMinutes,
                                          // Session 37. Only ever set while a booking is being
                                          // moved, so it doesn't block itself. See AvailabilityApi.
                                          @RequestParam(required = false) UUID excludeBookingId) {
        return availability.freeSlots(salonId, stylistId, date, durationMinutes, excludeBookingId).stream()
                .map(s -> new SlotResponse(s.start(), s.end(), s.stylistId()))
                .toList();
    }

    @Operation(summary = "\"Any available\" free slots across every active stylist at the salon", description = "Used for the customer-facing \"any stylist\" booking path — no stylist preference required.")
    @GetMapping("/slots/any")
    public List<SlotResponse> freeSlotsAnyStylist(@RequestParam UUID salonId,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                    @RequestParam int durationMinutes,
                                                    @RequestParam(required = false) UUID excludeBookingId) {
        return availability.freeSlotsAnyStylist(salonId, date, durationMinutes, excludeBookingId).stream()
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
