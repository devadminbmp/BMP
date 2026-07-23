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
}
