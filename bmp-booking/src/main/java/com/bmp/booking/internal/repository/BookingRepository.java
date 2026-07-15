package com.bmp.booking.internal.repository;

import com.bmp.booking.internal.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    Page<Booking> findByCustomerId(UUID customerId, Pageable pageable);
    Page<Booking> findByCustomerIdAndStatus(UUID customerId, com.bmp.booking.api.BookingStatus status, Pageable pageable);
    long countByBookingRefStartingWith(String prefix);
}
