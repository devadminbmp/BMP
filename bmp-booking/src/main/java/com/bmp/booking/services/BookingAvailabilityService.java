package com.bmp.booking.services;

import com.bmp.booking.dto.BookingDtos.BusyWindow;
import com.bmp.booking.entities.BookingServiceItem;
import com.bmp.booking.entities.SlotLock;
import com.bmp.booking.repositories.BookingServiceItemRepository;
import com.bmp.booking.repositories.SlotLockRepository;
import com.bmp.common.time.BmpTimeZone;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Session 8 (availability algorithm) — read-only support for bmp-salon's AvailabilityApi.
 * bmp-salon owns the "what are this stylist's working hours" question; this service answers
 * the complementary "what is this stylist already committed to on this date" question, since
 * that data (confirmed bookings, in-flight checkout holds) lives in booking_schema, not
 * salon_schema. Called over Feign — see bmp-salon's BookingServiceClient.
 */
@Service
public class BookingAvailabilityService {

    private final BookingServiceItemRepository itemRepo;
    private final SlotLockRepository lockRepo;

    public BookingAvailabilityService(BookingServiceItemRepository itemRepo, SlotLockRepository lockRepo) {
        this.itemRepo = itemRepo;
        this.lockRepo = lockRepo;
    }

    /**
     * @param excludeBookingId Session 37. Null in the ordinary case; set when RESCHEDULING, so a
     *                         booking doesn't collide with its own current slot. Moving a
     *                         60-minute service from 11:00 to 11:30 would otherwise be refused
     *                         because 11:00–12:00 is "busy" — with the very booking being moved.
     *                         Everyone else's bookings stay visible, so this can never let a
     *                         reschedule land on another customer.
     */
    public List<BusyWindow> getBusyWindows(UUID stylistId, LocalDate date, UUID excludeBookingId) {
        Instant dayStart = date.atStartOfDay(BmpTimeZone.ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(BmpTimeZone.ZONE).toInstant();

        List<BusyWindow> windows = new ArrayList<>();

        for (BookingServiceItem item : itemRepo.findBusyItemsForStylist(stylistId, dayStart, dayEnd, excludeBookingId)) {
            LocalTime start = item.getServiceStart().atZone(BmpTimeZone.ZONE).toLocalTime();
            LocalTime end = item.getServiceEnd().atZone(BmpTimeZone.ZONE).toLocalTime();
            windows.add(new BusyWindow(start, end, "booking"));
        }

        for (SlotLock lock : lockRepo.findActiveLocksForStylist(stylistId, date, Instant.now())) {
            windows.add(new BusyWindow(LocalTime.parse(lock.getStartTime()), LocalTime.parse(lock.getEndTime()), "slot_lock"));
        }

        return windows;
    }

    /**
     * Busy windows for EVERY stylist at a salon on one day, in two queries. Session 52.
     *
     * <h2>The problem this solves</h2>
     * bmp-salon's {@code freeSlotsAnyStylist} loops over the salon's team and calls
     * {@link #getBusyWindows} once per stylist — each one a separate cross-service HTTP round
     * trip. Six stylists meant six sequential calls for one "what's free today?", which is the
     * lag on the slot picker.
     *
     * <p>This does the same work in one item query plus one lock query, whatever the team size.
     *
     * @param stylistIds the salon's stylists. Slot locks have no salon column, so they are looked
     *                   up by id; an empty list skips that query entirely rather than emitting
     *                   {@code IN ()}, which is invalid SQL.
     * @return stylist id → their busy windows. A stylist with nothing booked is ABSENT from the
     *         map rather than present with an empty list — callers must use
     *         {@code getOrDefault(id, List.of())}, which is the same shape as asking individually
     *         and getting nothing back.
     */
    public Map<UUID, List<BusyWindow>> getBusyWindowsForSalon(
            UUID salonId, LocalDate date, Collection<UUID> stylistIds, UUID excludeBookingId) {

        Instant dayStart = date.atStartOfDay(BmpTimeZone.ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(BmpTimeZone.ZONE).toInstant();

        Map<UUID, List<BusyWindow>> out = new HashMap<>();

        for (BookingServiceItem item : itemRepo.findBusyItemsForSalon(
                salonId, dayStart, dayEnd, excludeBookingId)) {
            LocalTime start = item.getServiceStart().atZone(BmpTimeZone.ZONE).toLocalTime();
            LocalTime end = item.getServiceEnd().atZone(BmpTimeZone.ZONE).toLocalTime();
            out.computeIfAbsent(item.getAssignedStylistId(), k -> new ArrayList<>())
               .add(new BusyWindow(start, end, "booking"));
        }

        if (stylistIds != null && !stylistIds.isEmpty()) {
            for (SlotLock lock : lockRepo.findActiveLocksForStylists(stylistIds, date, Instant.now())) {
                out.computeIfAbsent(lock.getStylistId(), k -> new ArrayList<>())
                   .add(new BusyWindow(LocalTime.parse(lock.getStartTime()),
                                       LocalTime.parse(lock.getEndTime()), "slot_lock"));
            }
        }

        return out;
    }
}
