package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.SalonComboItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonComboItemRepository extends JpaRepository<SalonComboItem, UUID> {
}
