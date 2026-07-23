package com.bmp.booking.repositories;

import com.bmp.booking.entities.BookingServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BookingServiceItemRepository extends JpaRepository<BookingServiceItem, UUID> {
    List<BookingServiceItem> findByBookingId(UUID bookingId);

    /**
     * Availability algorithm support (Session 8): items that make a stylist busy during
     * [windowStart, windowEnd) on the day being queried. Joins to booking_schema.booking to
     * also exclude items whose parent booking is CANCELLED — item_status alone
     * (active/removed/completed) isn't updated by the cancel flow today, so relying on it
     * alone would still show a cancelled booking's slot as busy.
     */
    @Query("SELECT i FROM BookingServiceItem i JOIN Booking b ON b.id = i.bookingId " +
           "WHERE i.assignedStylistId = :stylistId " +
           "AND i.itemStatus = 'active' " +
           "AND b.status <> com.bmp.booking.api.BookingStatus.CANCELLED " +
           "AND i.serviceStart < :windowEnd AND i.serviceEnd > :windowStart")
    List<BookingServiceItem> findBusyItemsForStylist(
            @Param("stylistId") UUID stylistId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);
}
