package com.bmp.booking.repositories;

import com.bmp.booking.entities.BookingDisruption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingDisruptionRepository extends JpaRepository<BookingDisruption, UUID> {
}
