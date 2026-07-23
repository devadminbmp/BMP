package com.bmp.admin.repositories;

import com.bmp.admin.entities.BmpStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BmpStaffRepository extends JpaRepository<BmpStaff, UUID> {
    List<BmpStaff> findByRole(String role);
    Optional<BmpStaff> findByPhone(String phone);
    boolean existsByPhone(String phone);
}
