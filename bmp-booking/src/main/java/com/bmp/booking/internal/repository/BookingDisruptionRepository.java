package com.bmp.booking.internal.repository;

import com.bmp.booking.internal.entity.BookingDisruption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingDisruptionRepository extends JpaRepository<BookingDisruption, UUID> {
}
