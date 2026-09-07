package com.bmp.salon.repositories;

import com.bmp.salon.entities.StylistLeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Leave requests. V023 (Session 49). */
public interface StylistLeaveRequestRepository extends JpaRepository<StylistLeaveRequest, UUID> {

    /** A stylist's own leave, newest first. */
    List<StylistLeaveRequest> findByStylistIdOrderByStartsOnDesc(UUID stylistId);

    /** The salon's inbox. Oldest FIRST — somebody has been waiting longest, and leave is booked
     *  around real life, so a request for next week is more urgent than one for next month. */
    List<StylistLeaveRequest> findBySalonIdAndStatusOrderByStartsOnAsc(UUID salonId, String status);

    /** Everything for this salon, for the decided-history view. */
    List<StylistLeaveRequest> findBySalonIdOrderByStartsOnDesc(UUID salonId);

    /**
     * Does this stylist already have leave covering any part of the requested range?
     *
     * <h2>Why overlap and not equality</h2>
     * Somebody who has the 10th–12th approved and then asks for the 11th is not making a new
     * request, they are duplicating one — and if both are approved, cancelling one deletes
     * availability rows the other still needs, quietly putting them back on the calendar for a
     * day they are away. Refusing the overlap is much easier than reconciling it.
     *
     * <p>Two inclusive ranges overlap when each starts on or before the other ends.
     *
     * <p>Only pending and approved rows count. A declined or cancelled request is not leave.
     */
    @Query("""
           SELECT r FROM StylistLeaveRequest r
           WHERE r.stylistId = :stylistId
             AND r.status IN ('pending', 'approved')
             AND r.startsOn <= :endsOn
             AND r.endsOn >= :startsOn
           """)
    List<StylistLeaveRequest> findOverlapping(@Param("stylistId") UUID stylistId,
                                               @Param("startsOn") LocalDate startsOn,
                                               @Param("endsOn") LocalDate endsOn);
}
