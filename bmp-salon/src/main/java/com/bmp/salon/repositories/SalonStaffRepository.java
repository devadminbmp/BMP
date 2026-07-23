package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalonStaffRepository extends JpaRepository<SalonStaff, UUID> {

    /** Used by bmp-auth (via Feign, on every token mint) to resolve a user's current
     * OWNER/MANAGER salon scope for the JWT's role+salonId claims. A user is expected to
     * hold at most one staff seat at a time in this pass — "most recent" is the tiebreaker
     * if that invariant is ever violated. */
    Optional<SalonStaff> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    List<SalonStaff> findBySalonId(UUID salonId);

    boolean existsBySalonIdAndUserId(UUID salonId, UUID userId);
}
