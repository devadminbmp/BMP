package com.bmp.salon.repositories;

import com.bmp.salon.entities.StylistSalon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StylistSalonRepository extends JpaRepository<StylistSalon, UUID> {
    List<StylistSalon> findBySalonId(UUID salonId);
    List<StylistSalon> findBySalonIdAndStatus(UUID salonId, String status);
    Optional<StylistSalon> findBySalonIdAndStylistId(UUID salonId, UUID stylistId);

    /**
     * Every salon this stylist is linked to. Session 48.
     *
     * <p>The existing finders all start from the SALON — natural, because until now a stylist only
     * existed as somebody an owner had added. A self-registered stylist asks the opposite
     * question: "where do I actually work?", and before this there was no way to answer it.
     *
     * <p>Includes inactive links deliberately; the caller filters. A stylist who has left a salon
     * still needs to see that they were there.
     */
    List<StylistSalon> findByStylistId(UUID stylistId);
}
