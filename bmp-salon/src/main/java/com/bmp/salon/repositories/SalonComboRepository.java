package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonCombo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonComboRepository extends JpaRepository<SalonCombo, UUID> {
}
