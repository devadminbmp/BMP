package com.bmp.salon.repositories;

import com.bmp.salon.entities.StaffInvites;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffInvitesRepository extends JpaRepository<StaffInvites, UUID> {

    Optional<StaffInvites> findByTokenAndStatus(String token, String status);

    /** Session 15: the owner's "pending invites" list — codes sent but not yet redeemed.
     * Newest first, because the one they just created is the one they're looking for. */
    List<StaffInvites> findBySalonIdAndStatusOrderByCreatedAtDesc(UUID salonId, String status);

    /** Salon-scoped so an owner can only revoke invites belonging to their own salon. */
    Optional<StaffInvites> findByIdAndSalonId(UUID id, UUID salonId);
}
