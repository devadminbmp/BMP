package com.bmp.booking.repositories;

import com.bmp.booking.entities.BookingEvents;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingEventsRepository extends JpaRepository<BookingEvents, UUID> {
    List<BookingEvents> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);
}
