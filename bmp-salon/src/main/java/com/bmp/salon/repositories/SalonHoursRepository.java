package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalonHoursRepository extends JpaRepository<SalonHours, UUID> {
    List<SalonHours> findBySalonId(UUID salonId);
    Optional<SalonHours> findBySalonIdAndDayOfWeek(UUID salonId, int dayOfWeek);
}
