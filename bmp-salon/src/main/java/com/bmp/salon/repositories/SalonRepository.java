package com.bmp.salon.repositories;

import com.bmp.salon.entities.Salon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SalonRepository extends JpaRepository<Salon, UUID> {

    /**
     * Session 21: used by the customer-facing proximity search, which must only ever see
     * APPROVED salons. Before this, {@code near()} called {@code findAll()} and every
     * unreviewed salon was live to customers the moment it was created.
     */
    List<Salon> findByStatus(String status);

    /**
     * Accepts several statuses because 'approved' (the moderation flow) and 'active' (the dev
     * seed and pre-Session-21 rows) both mean publicly visible. See SalonService.PUBLICLY_VISIBLE.
     */
    List<Salon> findByStatusIn(List<String> statuses);

    long countByStatus(String status);

    /**
     * Take the next salon reference from the sequence: BMPS001, BMPS002, … V017 (Session 48).
     *
     * <p>{@code nextval} is atomic and never hands the same number to two callers, which is the
     * entire reason this is a sequence and not {@code count(*) + 1}. Two owners signing up in the
     * same second would both count N and both take N+1 — duplicate references, in the one field
     * support uses to tell salons apart.
     *
     * <p>Numbers are consumed even by transactions that roll back, so the series has gaps. That is
     * correct: a gap tells nobody anything, and avoiding gaps is exactly what forces you back to
     * counting rows.
     *
     * <p>Zero-padded to 3 for readability; the format grows on its own past 999 (BMPS1000), which
     * is fine — nothing parses this string.
     */
    @Query(value = "SELECT 'BMPS' || lpad(nextval('salon_schema.salon_reference_seq')::text, 3, '0')",
           nativeQuery = true)
    String allocateReference();
}
