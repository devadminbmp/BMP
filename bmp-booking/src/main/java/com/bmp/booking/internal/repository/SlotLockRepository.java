package com.bmp.booking.internal.repository;

import com.bmp.booking.internal.entity.SlotLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SlotLockRepository extends JpaRepository<SlotLock, UUID> {
}
