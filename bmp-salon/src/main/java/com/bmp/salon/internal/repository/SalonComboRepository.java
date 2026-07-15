package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.SalonCombo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonComboRepository extends JpaRepository<SalonCombo, UUID> {
}
