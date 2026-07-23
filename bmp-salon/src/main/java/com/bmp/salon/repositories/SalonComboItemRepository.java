package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonComboItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalonComboItemRepository extends JpaRepository<SalonComboItem, UUID> {
    List<SalonComboItem> findByComboId(UUID comboId);
    void deleteByComboId(UUID comboId);
}
