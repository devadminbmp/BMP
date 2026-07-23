package com.bmp.salon.repositories;

import com.bmp.salon.entities.Stylist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StylistRepository extends JpaRepository<Stylist, UUID> {
}
