package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalonServiceRepository extends JpaRepository<SalonService, UUID> {
    List<SalonService> findBySalonId(UUID salonId);
}
