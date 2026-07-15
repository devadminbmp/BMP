package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.StylistService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StylistServiceRepository extends JpaRepository<StylistService, UUID> {
    List<StylistService> findByStylistIdAndSalonId(UUID stylistId, UUID salonId);
}
