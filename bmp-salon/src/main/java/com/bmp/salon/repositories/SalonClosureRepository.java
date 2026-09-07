package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SalonClosureRepository extends JpaRepository<SalonClosure, UUID> {

    /**
     * Active closures overlapping {@code [from, to)}, for one salon.
     *
     * <p>Filtered in SQL rather than in Java: the availability algorithm calls this for every
     * search, and pulling a salon's entire closure history to discard all but one row would get
     * slower every year the salon stays on the platform.
     *
     * <p>Overlap test mirrors {@link SalonClosure#overlaps} exactly. Two copies of a comparison
     * that must agree is a risk, so if you change one, change the other — the entity method is
     * what the unit tests exercise, this one is what production actually runs.
     */
    @Query("""
           SELECT c FROM SalonClosure c
           WHERE c.salonId = :salonId
             AND c.cancelledAt IS NULL
             AND c.startsAt < :to
             AND :from < c.endsAt
           ORDER BY c.startsAt
           """)
    List<SalonClosure> findActiveOverlapping(@Param("salonId") UUID salonId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    /** Everything the owner should see on the closures panel — newest window first. */
    List<SalonClosure> findBySalonIdOrderByStartsAtDesc(UUID salonId);
}
