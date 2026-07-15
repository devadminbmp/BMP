package com.bmp.booking.internal.repository;

import com.bmp.booking.internal.entity.BookingModification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingModificationRepository extends JpaRepository<BookingModification, UUID> {
}
