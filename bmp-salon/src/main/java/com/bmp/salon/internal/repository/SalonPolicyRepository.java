package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.SalonPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SalonPolicyRepository extends JpaRepository<SalonPolicy, UUID> {
    Optional<SalonPolicy> findBySalonId(UUID salonId);
}
