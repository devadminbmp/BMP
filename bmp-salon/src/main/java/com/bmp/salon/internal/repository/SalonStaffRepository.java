package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.SalonStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalonStaffRepository extends JpaRepository<SalonStaff, UUID> {
}
