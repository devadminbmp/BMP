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

    // ---- V009 (Session 57): the assignment queue -------------------------------------------

    /**
     * The next person who should get a ticket at this tier: least-loaded first.
     *
     * <h2>This query IS the round robin</h2>
     * Ordering by {@code openTicketCount} rather than rotating a pointer means a NEW JOINER — count
     * zero — is picked first automatically, which is what "new members are included in the queue"
     * requires with no roster to maintain. It also self-corrects: an agent who closes their
     * tickets becomes eligible again immediately, where a strict rotation would keep dealing to
     * whoever is next regardless of what they are already carrying.
     *
     * <p>{@code tier > 0} excludes finance and read-only analysts by construction. They may read
     * the queue; they can never be handed an item in it.
     *
     * <p>Tie broken by id so the order is deterministic — an unstable ORDER BY makes an assignment
     * bug unreproducible, which is the worst kind to chase.
     *
     * <p>Backed by {@code idx_staff_assignable}.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM BmpStaff s
            WHERE s.tier = :tier
              AND s.tier > 0
              AND s.status = 'active'
              AND s.acceptingTickets = true
            ORDER BY s.openTicketCount ASC, s.id ASC
            LIMIT 1
            """)
    Optional<BmpStaff> findNextAssignee(@org.springframework.data.repository.query.Param("tier") short tier);

    /** Everyone on the ladder at a tier — for a lead looking at their team's load. */
    List<BmpStaff> findByTierAndStatusOrderByOpenTicketCountAsc(short tier, String status);

    /** The staff list, newest first — what the master admin manages employees from. */
    List<BmpStaff> findAllByOrderByCreatedAtDesc();
}
