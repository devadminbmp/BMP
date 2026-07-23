package com.bmp.salon.repositories;

import com.bmp.salon.entities.Salon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonRepository extends JpaRepository<Salon, UUID> {
}
