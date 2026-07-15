package com.bmp.salon.internal.repository;

import com.bmp.salon.internal.entity.StaffInvites;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StaffInvitesRepository extends JpaRepository<StaffInvites, UUID> {
}
