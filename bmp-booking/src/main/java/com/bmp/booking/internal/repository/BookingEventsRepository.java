package com.bmp.booking.internal.repository;

import com.bmp.booking.internal.entity.BookingEvents;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingEventsRepository extends JpaRepository<BookingEvents, UUID> {
    List<BookingEvents> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);
}
