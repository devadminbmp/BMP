package com.bmp.booking.repositories;

import com.bmp.booking.entities.BookingServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingServiceItemRepository extends JpaRepository<BookingServiceItem, UUID> {
    List<BookingServiceItem> findByBookingId(UUID bookingId);
}
