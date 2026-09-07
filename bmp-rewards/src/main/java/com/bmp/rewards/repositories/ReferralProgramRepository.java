package com.bmp.rewards.repositories;

import com.bmp.rewards.entities.ReferralProgram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Versions of the referral offer, newest first. Session 64 (V006). */
public interface ReferralProgramRepository extends JpaRepository<ReferralProgram, UUID> {

    /**
     * The version in force at {@code at}.
     *
     * <p>Filtered on {@code effective_from <= at} rather than just taking the newest row, so a
     * FUTURE-dated version — scheduling a change for Monday — does not start applying the moment it
     * is saved. Without the filter, "schedule it" and "do it now" would be the same thing.
     */
    @Query("""
           SELECT p FROM ReferralProgram p
           WHERE p.effectiveFrom <= :at
           ORDER BY p.effectiveFrom DESC, p.createdAt DESC
           """)
    List<ReferralProgram> findEffectiveAt(@Param("at") Instant at);

    default Optional<ReferralProgram> currentAt(Instant at) {
        return findEffectiveAt(at).stream().findFirst();
    }

    /** Every version, newest first — the history the console shows. */
    List<ReferralProgram> findAllByOrderByEffectiveFromDesc();
}
