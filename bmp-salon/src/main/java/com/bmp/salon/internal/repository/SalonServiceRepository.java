package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.SalonService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalonServiceRepository extends JpaRepository<SalonService, UUID> {
    List<SalonService> findBySalonId(UUID salonId);
}
