package com.bmp.booking.repositories;

import com.bmp.booking.entities.SlotLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SlotLockRepository extends JpaRepository<SlotLock, UUID> {
}
