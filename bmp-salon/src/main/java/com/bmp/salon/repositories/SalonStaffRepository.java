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

    /** Session 15 (owner team management): scoping the lookup by salon means an owner can
     * never touch a seat that isn't theirs, even by guessing a staff id from another salon. */
    Optional<SalonStaff> findByIdAndSalonId(UUID id, UUID salonId);

    /**
     * Every salon this user is staff at, any role. Session 48.
     *
     * <p>Added for the one-salon-per-owner guard — findFirstByUserIdOrderByCreatedAtDesc returns
     * only the newest row, which cannot answer "do they own one anywhere" for somebody who is a
     * stylist at two places and an owner at a third.
     */
    List<SalonStaff> findByUserId(UUID userId);
}
