package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SalonPolicyRepository extends JpaRepository<SalonPolicy, UUID> {
    Optional<SalonPolicy> findBySalonId(UUID salonId);
}
