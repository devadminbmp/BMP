package com.bmp.salon.repositories;

import com.bmp.salon.entities.Salon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalonRepository extends JpaRepository<Salon, UUID> {

    /**
     * Session 21: used by the customer-facing proximity search, which must only ever see
     * APPROVED salons. Before this, {@code near()} called {@code findAll()} and every
     * unreviewed salon was live to customers the moment it was created.
     */
    List<Salon> findByStatus(String status);

    /**
     * Accepts several statuses because 'approved' (the moderation flow) and 'active' (the dev
     * seed and pre-Session-21 rows) both mean publicly visible. See SalonService.PUBLICLY_VISIBLE.
     */
    List<Salon> findByStatusIn(List<String> statuses);

    long countByStatus(String status);
}
