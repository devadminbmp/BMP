package com.bmp.booking.internal.controller;

import com.bmp.booking.internal.dto.BookingDtos.*;
import com.bmp.booking.internal.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-25: booking_schema.booking CRUD + append-only booking_events read. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{bookingId}")
    public BookingResponse getById(@PathVariable UUID bookingId) {
        return service.getById(bookingId);
    }

    @GetMapping
    public PagedBookings list(@RequestParam UUID customerId,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return service.list(customerId, status, page, size);
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingResponse cancel(@PathVariable UUID bookingId, @RequestBody CancelRequest req) {
        return service.cancel(bookingId, req);
    }

    @GetMapping("/{bookingId}/events")
    public List<EventResponse> events(@PathVariable UUID bookingId) {
        return service.listEvents(bookingId);
    }
}
