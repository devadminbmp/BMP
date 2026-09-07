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
     * Every busy item for a WHOLE SALON on one day, in a single query. Session 52.
     *
     * <h2>Why this exists: the availability picker was doing N round trips</h2>
     * {@code freeSlotsAnyStylist} loops over the salon's stylists and calls {@code freeSlots} for
     * each, and every one of those makes a separate cross-service Feign call to
     * {@code /busy-windows} for one stylist. A salon with six stylists therefore produced six
     * HTTP round trips between bmp-salon and bmp-booking, serially, for a single "what's free
     * today?" — which is exactly the lag Darshan reported on the slot picker.
     *
     * <p>One query returns the lot. The caller groups by {@code assignedStylistId}.
     *
     * <p>Same predicates as {@link #findBusyItemsForStylist} — active items, non-cancelled
     * bookings, overlapping the window — because the two must never disagree about what "busy"
     * means. If one is changed the other has to change with it.
     */
    @Query("SELECT i FROM BookingServiceItem i JOIN Booking b ON b.id = i.bookingId " +
           "WHERE b.salonId = :salonId " +
           "AND i.assignedStylistId IS NOT NULL " +
           "AND i.itemStatus = 'active' " +
           "AND b.status <> com.bmp.booking.api.BookingStatus.CANCELLED " +
           "AND (:excludeBookingId IS NULL OR i.bookingId <> :excludeBookingId) " +
           "AND i.serviceStart < :windowEnd AND i.serviceEnd > :windowStart")
    List<BookingServiceItem> findBusyItemsForSalon(
            @Param("salonId") UUID salonId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("excludeBookingId") UUID excludeBookingId);

    /**
     * A stylist's own upcoming appointments. Session 48.
     *
     * <h2>Why not reuse findUpcomingForSalon with a filter</h2>
     * That query is salon-scoped and takes no stylist. Adding an optional stylistId to it would
     * mean one query serving both "the salon's queue" and "my queue", and the difference between
     * them is the entire privacy boundary — the salon's queue is every customer in the building.
     * A separate query makes "this one is stylist-scoped" a property of the SQL rather than of
     * whichever caller remembered to pass the parameter.
     *
     * <p>{@code salonId} is still required and still checked. The stylist works at exactly one
     * salon (V021), and pinning the query to it means a stale or wrong stylistId cannot reach
     * another salon's bookings.
     *
     * <p>Cancelled/completed/no-show excluded, same reasoning as the salon version: a forward
     * list is a work queue, and things that aren't happening don't belong in it.
     */
    @Query("SELECT i FROM BookingServiceItem i JOIN Booking b ON b.id = i.bookingId " +
           "WHERE b.salonId = :salonId " +
           "AND i.assignedStylistId = :stylistId " +
           "AND i.serviceStart >= :from " +
           "AND i.itemStatus = 'active' " +
           "AND b.status NOT IN (com.bmp.booking.api.BookingStatus.CANCELLED, " +
           "                     com.bmp.booking.api.BookingStatus.COMPLETED, " +
           "                     com.bmp.booking.api.BookingStatus.NO_SHOW) " +
           "ORDER BY i.serviceStart ASC")
    org.springframework.data.domain.Page<BookingServiceItem> findUpcomingForStylist(
            @Param("salonId") UUID salonId,
            @Param("stylistId") UUID stylistId,
            @Param("from") Instant from,
            org.springframework.data.domain.Pageable pageable);

    /**
     * A stylist's own past appointments — the work they have done here.
     *
     * <p>Ordered by when the APPOINTMENT was, newest first. Cancelled bookings are included:
     * unlike the forward queue, a stylist looking back may reasonably want to see that a slot
     * was cancelled rather than wonder why the afternoon looks empty.
     */
    @Query("SELECT i FROM BookingServiceItem i JOIN Booking b ON b.id = i.bookingId " +
           "WHERE b.salonId = :salonId " +
           "AND i.assignedStylistId = :stylistId " +
           "AND i.serviceStart < :before " +
           "ORDER BY i.serviceStart DESC")
    org.springframework.data.domain.Page<BookingServiceItem> findPastForStylist(
            @Param("salonId") UUID salonId,
            @Param("stylistId") UUID stylistId,
            @Param("before") Instant before,
            org.springframework.data.domain.Pageable pageable);

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

    /**
     * Completed work per stylist, over a window. Session 48.
     *
     * <h2>What "income" means here, precisely</h2>
     * The sum of {@code price_paise_snapshot} for items whose own {@code item_status} is
     * 'completed', on bookings belonging to this salon, starting inside the window. It is the
     * value of work DONE — not billed, not collected, and not the stylist's pay.
     *
     * <p>Three deliberate choices, each of which would otherwise produce a plausible wrong number:
     * <ul>
     *   <li><b>item_status, not booking status.</b> A booking can be partly completed — two
     *       services with one stylist each, one done and one no-showed. Summing by booking would
     *       credit both stylists for work only one of them did.</li>
     *   <li><b>The SNAPSHOT price, not today's price.</b> Session 34 froze the price onto the item
     *       for exactly this reason: a salon that raises its prices must not retroactively change
     *       what last month's work was worth.</li>
     *   <li><b>serviceStart, not created_at.</b> "How much did Priya do in March" means work
     *       performed in March, not bookings made in March.</li>
     * </ul>
     *
     * <p>Returns rows of [stylistId, totalPaise, completedCount]. Unassigned items (no stylist)
     * are excluded — they are real revenue but belong to nobody, and silently attributing them to
     * a NULL row makes a total that does not reconcile.
     */
    /**
     * Native, not JPQL: {@code price_paise_snapshot} is mapped through {@code MoneyAttributeConverter}
     * to the {@code Money} record, and JPQL cannot navigate into a converted basic-valued path
     * (there is no {@code i.pricePaiseSnapshot.paise} at the JPQL level — Hibernate never sees a
     * sub-attribute to resolve). Summing the raw column natively sidesteps that entirely.
     */
    @Query(nativeQuery = true, value = """
           SELECT assigned_stylist_id, SUM(price_paise_snapshot), COUNT(*)
           FROM booking_schema.booking_service_item
           WHERE assigned_stylist_id IS NOT NULL
             AND item_status = 'completed'
             AND service_start >= :from AND service_start < :to
             AND booking_id IN (SELECT id FROM booking_schema.booking WHERE salon_id = :salonId)
           GROUP BY assigned_stylist_id
           """)
    List<Object[]> sumCompletedByStylist(@Param("salonId") UUID salonId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);
}
