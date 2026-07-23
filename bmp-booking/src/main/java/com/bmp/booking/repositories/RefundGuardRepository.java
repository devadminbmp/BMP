package com.bmp.booking.repositories;

import com.bmp.booking.entities.RefundGuard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundGuardRepository extends JpaRepository<RefundGuard, UUID> {
}
