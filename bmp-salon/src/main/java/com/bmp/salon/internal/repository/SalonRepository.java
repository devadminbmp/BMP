package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.Salon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonRepository extends JpaRepository<Salon, UUID> {
}
