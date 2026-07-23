package com.bmp.salon.repositories;

import com.bmp.salon.entities.StaffInvites;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StaffInvitesRepository extends JpaRepository<StaffInvites, UUID> {

    Optional<StaffInvites> findByTokenAndStatus(String token, String status);
}
