package com.bmp.booking.repositories;

import com.bmp.booking.entities.BookingModification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingModificationRepository extends JpaRepository<BookingModification, UUID> {
}
