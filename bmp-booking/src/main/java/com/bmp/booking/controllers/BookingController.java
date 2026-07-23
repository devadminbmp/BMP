package com.bmp.booking.controllers;

import com.bmp.booking.dto.BookingDtos.*;
import com.bmp.booking.services.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-25: booking_schema.booking CRUD + append-only booking_events read. */
@Tag(name = "Bookings", description = "booking + booking_service_item CRUD, append-only booking_events. No per-endpoint authorization yet (open to any valid token) — a follow-up ticket.")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @Operation(summary = "Create a booking")
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a booking by id")
    @GetMapping("/{bookingId}")
    public BookingResponse getById(@PathVariable UUID bookingId) {
        return service.getById(bookingId);
    }

    @Operation(summary = "List a customer's bookings", description = "Paginated, optionally filtered by status.")
    @GetMapping
    public PagedBookings list(@RequestParam UUID customerId,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return service.list(customerId, status, page, size);
    }

    @Operation(summary = "Cancel a booking", description = "Validated against the booking state machine (e.g. a PENDING booking can be cancelled by the customer; not every state transition is allowed from every actor).")
    @PostMapping("/{bookingId}/cancel")
    public BookingResponse cancel(@PathVariable UUID bookingId, @RequestBody CancelRequest req) {
        return service.cancel(bookingId, req);
    }

    @Operation(summary = "List a booking's event history", description = "Append-only audit trail (booking_events) — every status transition, never mutated or deleted.")
    @GetMapping("/{bookingId}/events")
    public List<EventResponse> events(@PathVariable UUID bookingId) {
        return service.listEvents(bookingId);
    }
}
