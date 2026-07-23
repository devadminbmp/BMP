package com.bmp.booking.services;

import com.bmp.booking.api.BookingStatus;
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

    private final BookingRepository bookings;
    private final BookingServiceItemRepository items;
    private final BookingEventsRepository events;
    private final SalonAvailabilityClient availability;
    private final ObjectMapper mapper = new ObjectMapper();

    public BookingService(BookingRepository bookings, BookingServiceItemRepository items,
                           BookingEventsRepository events, SalonAvailabilityClient availability) {
        this.bookings = bookings;
        this.items = items;
        this.events = events;
        this.availability = availability;
    }

    @Transactional
    public BookingResponse create(CreateBookingRequest req) {
        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        String prefix = "BMP-" + year + "-";
        // NOTE: same count-based simplification as support_ticket's ticketRef (BMP-29) —
        // replace with a real Postgres sequence before go-live, not safe under concurrency.
        long seq = bookings.countByBookingRefStartingWith(prefix) + 1;
        String bookingRef = prefix + String.format("%05d", seq);

        Money total = Money.ZERO;
        for (ItemRequest item : req.items()) {
            total = total.plus(Money.ofPaise(item.pricePaise()));
        }
        // TODO(Phase 3 / inter-service): commission and policy_snapshot should come from a
        // live call to bmp-salon-service (salon_policy). Placeholder here in this CRUD-first pass.
        Money commission = total.percentBps(1200);

        // Resolve + validate every item's stylist/slot BEFORE writing anything — a booking
        // with some items validated and others not is worse than rejecting the whole request.
        List<UUID> resolvedStylistIds = new java.util.ArrayList<>();
        for (ItemRequest item : req.items()) {
            resolvedStylistIds.add(resolveAndValidateSlot(req.salonId(), item));
        }

        Booking booking = new Booking(bookingRef, req.salonId(), req.customerId(), BookingStatus.PENDING,
                total, Money.ZERO, commission, "{}", true, null);
        booking = bookings.save(booking);

        List<ItemResponse> itemResponses = new java.util.ArrayList<>();
        List<ItemRequest> reqItems = req.items();
        for (int i = 0; i < reqItems.size(); i++) {
            ItemRequest item = reqItems.get(i);
            UUID assignedStylistId = resolvedStylistIds.get(i);
            Instant end = item.start().plus(Duration.ofMinutes(item.durationMinutes()));
            BookingServiceItem entity = new BookingServiceItem(booking.getId(), item.serviceId(), assignedStylistId,
                    item.selectionType(), item.start(), end, item.nameSnapshot(), Money.ofPaise(item.pricePaise()),
                    item.durationMinutes(), item.durationMinutes(), "active");
            entity = items.save(entity);
            itemResponses.add(toItemResponse(entity));
        }

        recordEvent(booking.getId(), "CREATED", "customer", req.customerId(), Map.of());
        return toResponse(booking, itemResponses);
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
     */
    private UUID resolveAndValidateSlot(UUID salonId, ItemRequest item) {
        LocalDate date = item.start().atZone(BmpTimeZone.ZONE).toLocalDate();
        LocalTime requestedStart = item.start().atZone(BmpTimeZone.ZONE).toLocalTime();

        if (item.stylistId() != null) {
            List<AvailabilitySlot> slots = availability.freeSlots(salonId, item.stylistId(), date, item.durationMinutes());
            boolean stillFree = slots.stream().anyMatch(s -> s.start().equals(requestedStart));
            if (!stillFree) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "SLOT_NOT_AVAILABLE: requested stylist/time is no longer free — someone else may have booked it first");
            }
            return item.stylistId();
        }

        // any_available: no stylist attached to the request — find any stylist who still
        // has this exact start time free and assign them.
        List<AvailabilitySlot> anySlots = availability.freeSlotsAnyStylist(salonId, date, item.durationMinutes());
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

    @Transactional
    public BookingResponse cancel(UUID id, CancelRequest req) {
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        try {
            // Both PENDING->CANCELLED and CONFIRMED->CANCELLED are valid CUSTOMER transitions
            // per BookingStatus.java (PENDING case added for BMP-25).
            b.getStatus().assertTransition(BookingStatus.CANCELLED, BookingStatus.Actor.CUSTOMER);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        b.setStatus(BookingStatus.CANCELLED);
        b.touch();
        recordEvent(id, "CANCELLED", "customer", b.getCustomerId(),
                req.reason() == null ? Map.of() : Map.of("reason", req.reason()));
        List<ItemResponse> itemResponses = items.findByBookingId(id).stream().map(this::toItemResponse).toList();
        return toResponse(b, itemResponses);
    }

    public List<EventResponse> listEvents(UUID bookingId) {
        return events.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(e -> new EventResponse(e.getEventType(), e.getActorType(), e.getActorId(), e.getCreatedAt()))
                .toList();
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
                b.getCreatedAt(), itemResponses);
    }
}
