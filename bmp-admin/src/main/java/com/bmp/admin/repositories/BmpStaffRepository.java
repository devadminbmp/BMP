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

    // ---- Session 20: console login + staff management ------------------------------------

    /**
     * Case-insensitive, because people type their email however they please and an account
     * that only works in lowercase generates a support ticket on day one. Matches the
     * functional unique index in V003 (`lower(email)`).
     */
    Optional<BmpStaff> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** The staff list, newest first — what the master admin manages employees from. */
    List<BmpStaff> findAllByOrderByCreatedAtDesc();
}
