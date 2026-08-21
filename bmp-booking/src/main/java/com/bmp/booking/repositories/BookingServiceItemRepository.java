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
     * Session 37 — "what's coming up?", the salon's most-asked question.
     *
     * <h2>Why the existing history query can't answer it</h2>
     * {@code findBySalonIdOrderByCreatedAtDesc} orders by when the booking was MADE. A booking
     * created yesterday for next month therefore sorts above one created last week for tomorrow.
     * That is right for history — "what did we take recently" — and useless for an upcoming
     * view, which has to order by when the APPOINTMENT is.
     *
     * <p>The appointment time lives on the item, not the booking, so the query starts here and
     * the caller loads the parent bookings once by id.
     *
     * <h2>Cancelled bookings are excluded, unlike the day view</h2>
     * The day view keeps them so a manager can see a slot freed up on the timeline. A forward
     * list is a work queue: a cancelled booking is not work, and padding the queue with things
     * that aren't happening is how people stop trusting it.
     *
     * <p>Returns items; two items of the same booking produce two rows, which the caller
     * collapses. Deliberate — the alternative is a DISTINCT-on-booking subquery that then can't
     * order by the item's start time, which is the whole point.
     */
    @Query("SELECT i FROM BookingServiceItem i JOIN Booking b ON b.id = i.bookingId " +
           "WHERE b.salonId = :salonId " +
           "AND i.serviceStart >= :from " +
           "AND i.itemStatus = 'active' " +
           "AND b.status NOT IN (com.bmp.booking.api.BookingStatus.CANCELLED, " +
           "                     com.bmp.booking.api.BookingStatus.COMPLETED, " +
           "                     com.bmp.booking.api.BookingStatus.NO_SHOW) " +
           "ORDER BY i.serviceStart ASC")
    List<BookingServiceItem> findUpcomingForSalon(
            @Param("salonId") UUID salonId,
            @Param("from") Instant from,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Availability algorithm support (Session 8): items that make a stylist busy during
     * [windowStart, windowEnd) on the day being queried. Joins to booking_schema.booking to
     * also exclude items whose parent booking is CANCELLED — item_status alone
     * (active/removed/completed) isn't updated by the cancel flow today, so relying on it
     * alone would still show a cancelled booking's slot as busy.
     *
     * <h2>Session 37: {@code excludeBookingId}</h2>
     * Null in the ordinary case. Set only when RESCHEDULING, and without it the feature does not
     * work at all for the commonest move:
     *
     * <p>A customer moves a 60-minute colour from 11:00 to 11:30. The availability check asks
     * "is 11:30–12:30 free for Meera?" — and this query answers no, because <b>the booking being
     * moved is still sitting at 11:00–12:00</b>. The item conflicts with itself. Every short
     * move, which is most of them, would be refused with "that slot is taken", naming a slot the
     * customer already owns.
     *
     * <p>Excluding the whole BOOKING rather than the single item is deliberate: a booking of a
     * cut then a colour is moved as a unit, and the two items must not block each other while
     * the new times are being validated.
     *
     * <p>The safety property still holds — everyone ELSE's bookings remain visible, so a
     * reschedule can never land on another customer.
     */
    @Query("SELECT i FROM BookingServiceItem i JOIN Booking b ON b.id = i.bookingId " +
           "WHERE i.assignedStylistId = :stylistId " +
           "AND i.itemStatus = 'active' " +
           "AND b.status <> com.bmp.booking.api.BookingStatus.CANCELLED " +
           "AND (:excludeBookingId IS NULL OR i.bookingId <> :excludeBookingId) " +
           "AND i.serviceStart < :windowEnd AND i.serviceEnd > :windowStart")
    List<BookingServiceItem> findBusyItemsForStylist(
            @Param("stylistId") UUID stylistId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("excludeBookingId") UUID excludeBookingId);

    /**
     * Session 16 — the manager desk's day view.
     *
     * <p>A salon's day is a list of SERVICE ITEMS, not of bookings: one booking can be a cut at
     * 11:00 with Ravi and a colour at 11:45 with Meera, and those are two different things
     * happening on two different people's calendars. Returning bookings would force the client
     * to flatten them and lose the ordering.
     *
     * <p>Items carry no salonId of their own, so the salon filter goes through a subquery on
     * booking — cheaper than a join here because we only need the item rows, and the caller
     * loads the parent bookings once by id afterwards (bounded by a single day's volume).
     *
     * <p>CANCELLED bookings are deliberately NOT filtered out: a manager wants to see that a
     * 3pm slot freed up. The caller decides how to display them.
     */
    @Query("SELECT i FROM BookingServiceItem i " +
           "WHERE i.serviceStart >= :from AND i.serviceStart < :to " +
           "AND (:stylistId IS NULL OR i.assignedStylistId = :stylistId) " +
           "AND i.bookingId IN (SELECT b.id FROM Booking b WHERE b.salonId = :salonId) " +
           "ORDER BY i.serviceStart ASC")
    List<BookingServiceItem> findSalonDayItems(
            @Param("salonId") UUID salonId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("stylistId") UUID stylistId);

    /**
     * Session 21: how many DISTINCT bookings start in a window, platform-wide.
     *
     * <p>Distinct because a booking of three services is one booking — counting items would
     * make the console's "bookings today" tile silently inflate on multi-service days, which
     * is exactly the kind of wrong number nobody questions.
     *
     * <p>Cancelled bookings are excluded: a tile counting appointments that aren't happening
     * would be worse than no tile.
     */
    @Query("SELECT COUNT(DISTINCT i.bookingId) FROM BookingServiceItem i " +
           "WHERE i.serviceStart >= :from AND i.serviceStart < :to " +
           "AND i.bookingId IN (SELECT b.id FROM Booking b " +
           "                    WHERE b.status <> com.bmp.booking.api.BookingStatus.CANCELLED)")
    long countDistinctBookingsStartingBetween(@Param("from") Instant from, @Param("to") Instant to);
}
