package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.StylistAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StylistAvailabilityRepository extends JpaRepository<StylistAvailability, UUID> {
}
