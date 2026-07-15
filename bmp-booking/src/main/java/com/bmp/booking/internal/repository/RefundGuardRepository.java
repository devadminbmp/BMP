package com.bmp.booking.internal.repository;

import com.bmp.booking.internal.entity.RefundGuard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundGuardRepository extends JpaRepository<RefundGuard, UUID> {
}
