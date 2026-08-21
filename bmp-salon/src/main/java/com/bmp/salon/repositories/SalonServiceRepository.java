package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalonServiceRepository extends JpaRepository<SalonService, UUID> {
    List<SalonService> findBySalonId(UUID salonId);

    /**
     * Menus for MANY salons at once. V011, Session 40.
     *
     * <p>The discovery list shows a "from ₹350" price per salon, which is a MIN over that salon's
     * services. Fetching per salon would be an N+1 on the platform's most-viewed screen; the
     * caller groups these once instead.
     */
    List<SalonService> findBySalonIdIn(List<UUID> salonIds);
}
