package com.bmp.salon.repositories;

import com.bmp.salon.entities.StylistAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StylistAvailabilityRepository extends JpaRepository<StylistAvailability, UUID> {
}
