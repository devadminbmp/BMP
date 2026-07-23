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

    public List<BusyWindow> getBusyWindows(UUID stylistId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(BmpTimeZone.ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(BmpTimeZone.ZONE).toInstant();

        List<BusyWindow> windows = new ArrayList<>();

        for (BookingServiceItem item : itemRepo.findBusyItemsForStylist(stylistId, dayStart, dayEnd)) {
            LocalTime start = item.getServiceStart().atZone(BmpTimeZone.ZONE).toLocalTime();
            LocalTime end = item.getServiceEnd().atZone(BmpTimeZone.ZONE).toLocalTime();
            windows.add(new BusyWindow(start, end, "booking"));
        }

        for (SlotLock lock : lockRepo.findActiveLocksForStylist(stylistId, date, Instant.now())) {
            windows.add(new BusyWindow(LocalTime.parse(lock.getStartTime()), LocalTime.parse(lock.getEndTime()), "slot_lock"));
        }

        return windows;
    }
}
