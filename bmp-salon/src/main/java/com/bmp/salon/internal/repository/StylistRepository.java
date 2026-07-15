package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.Stylist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StylistRepository extends JpaRepository<Stylist, UUID> {
}
