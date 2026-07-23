package com.bmp.booking.repositories;

import com.bmp.booking.entities.SlotLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SlotLockRepository extends JpaRepository<SlotLock, UUID> {

    /**
     * Availability algorithm support (Session 8): a stylist's still-active holds on
     * lockDate (release_reason IS NULL means never released; expires_at > now means the
     * 5-minute checkout hold hasn't lapsed). These count as busy the same as a confirmed
     * booking, so two customers can't be shown the same slot mid-checkout.
     */
    @Query("SELECT s FROM SlotLock s WHERE s.stylistId = :stylistId AND s.lockDate = :lockDate " +
           "AND s.releaseReason IS NULL AND s.expiresAt > :now")
    List<SlotLock> findActiveLocksForStylist(
            @Param("stylistId") UUID stylistId,
            @Param("lockDate") LocalDate lockDate,
            @Param("now") Instant now);
}
