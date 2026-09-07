package com.bmp.salon.repositories;

import com.bmp.salon.entities.Stylist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StylistRepository extends JpaRepository<Stylist, UUID> {

    /**
     * The stylist profile belonging to a user account. Session 48.
     *
     * <p>One profile per user by convention rather than by constraint — the column predates this
     * feature and back-filling a unique index over rows created by the invite flow is a migration
     * with a decision behind it. StylistService checks before creating, and the window between
     * check and insert is one person tapping one button twice, not a concurrent-writer race.
     */
    Optional<Stylist> findFirstByUserId(UUID userId);
}
