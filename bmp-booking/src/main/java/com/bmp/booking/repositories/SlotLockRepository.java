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

    /**
     * Active checkout holds for MANY stylists at once. Session 52.
     *
     * <p>The salon-wide availability query batches its busy-window lookup into one call; this is
     * the slot-lock half of it. Takes the stylist ids rather than a salonId because slot_lock has
     * no salon column — the caller already knows the salon's team.
     *
     * <p>An empty id list would produce {@code IN ()}, which is invalid SQL in some dialects, so
     * the caller must skip this when the salon has no stylists.
     */
    @Query("SELECT s FROM SlotLock s WHERE s.stylistId IN :stylistIds AND s.lockDate = :lockDate " +
           "AND s.releaseReason IS NULL AND s.expiresAt > :now")
    List<SlotLock> findActiveLocksForStylists(
            @Param("stylistIds") java.util.Collection<UUID> stylistIds,
            @Param("lockDate") LocalDate lockDate,
            @Param("now") Instant now);
}
