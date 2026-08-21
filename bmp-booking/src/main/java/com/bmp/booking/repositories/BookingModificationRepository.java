package com.bmp.booking.repositories;

import com.bmp.booking.entities.BookingModification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * The reschedule trail. Session 37.
 *
 * <p>{@code booking_modification} has existed in the schema since V002 and, until this session,
 * had <b>no repository and not one row</b> — rescheduling was described in {@code BookingStatus}
 * javadoc (wrongly, naming columns that don't exist on {@code booking}) and never built.
 *
 * <p>Append-only by convention: nothing here updates or deletes. A record of what an appointment
 * used to be is worth having precisely on the days somebody wishes it said something else.
 */
public interface BookingModificationRepository extends JpaRepository<BookingModification, UUID> {

    /** Newest first — "what changed most recently" is the question support asks. */
    List<BookingModification> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);
}
