package com.bmp.admin.repositories;

import com.bmp.admin.entities.QueueConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Assignment mode per tier. Session 59. One row per tier — see uq_queue_config_tier. */
public interface QueueConfigRepository extends JpaRepository<QueueConfig, UUID> {

    Optional<QueueConfig> findByTier(short tier);

    List<QueueConfig> findAllByOrderByTierAsc();
}
