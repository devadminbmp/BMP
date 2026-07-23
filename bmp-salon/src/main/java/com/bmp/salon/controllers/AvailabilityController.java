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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Session 8 — the first real consumer-facing surface for the availability algorithm.
 * See AvailabilityService for the actual interval math and every design decision behind it.
 * No per-endpoint authorization yet (open to any valid token, same as booking/payment/etc
 * as of this session) — a follow-up ticket, same as everywhere else this is true.
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
                                          @RequestParam int durationMinutes) {
        return availability.freeSlots(salonId, stylistId, date, durationMinutes).stream()
                .map(s -> new SlotResponse(s.start(), s.end(), s.stylistId()))
                .toList();
    }

    @Operation(summary = "\"Any available\" free slots across every active stylist at the salon", description = "Used for the customer-facing \"any stylist\" booking path — no stylist preference required.")
    @GetMapping("/slots/any")
    public List<SlotResponse> freeSlotsAnyStylist(@RequestParam UUID salonId,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                    @RequestParam int durationMinutes) {
        return availability.freeSlotsAnyStylist(salonId, date, durationMinutes).stream()
                .map(s -> new SlotResponse(s.start(), s.end(), s.stylistId()))
                .toList();
    }

    @Operation(summary = "Block a walk-in (the <5-second front-desk quick-add)", description = "One overlap check against existing bookings/holds/breaks/leave/other walk-ins, then a single insert. Returns 409 if the requested window isn't actually free. Deliberately does NOT re-validate against the stylist's declared working hours — see AvailabilityService javadoc for why.")
    @PostMapping("/walk-in")
    public ResponseEntity<Void> blockWalkIn(@Valid @RequestBody BlockWalkInRequest req) {
        availability.blockWalkIn(req.salonId(), req.stylistId(), req.date(), req.start(), req.durationMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
