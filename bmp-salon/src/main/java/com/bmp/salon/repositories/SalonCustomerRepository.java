package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonCustomer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * The salon's own customer book. Session 52, V026.
 *
 * <p>EVERY method here is salon-scoped, without exception. One salon's contact list must never be
 * reachable from another's — there is no legitimate query in this product that spans salons, so
 * there is no method here that can. That is enforced by shape rather than by remembering to pass a
 * filter, because the reviewer of the next feature will not remember.
 */
public interface SalonCustomerRepository extends JpaRepository<SalonCustomer, UUID> {

    /** The counter's primary lookup: the number the person just read out. */
    Optional<SalonCustomer> findBySalonIdAndPhone(UUID salonId, String phone);

    /** Belt-and-braces for reads by id — never load a row that belongs to a different salon. */
    Optional<SalonCustomer> findByIdAndSalonId(UUID id, UUID salonId);

    /**
     * Type-ahead at the counter. Matches a partial phone number OR a partial name, because the
     * receptionist sometimes has the number and sometimes only remembers "Priya".
     *
     * <p>{@code lower(...)} on the name rather than ILIKE so it behaves identically on any
     * collation; the phone side is digits, so case never arises.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT c FROM SalonCustomer c
            WHERE c.salonId = :salonId
              AND (c.phone LIKE CONCAT(:q, '%')
                   OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY c.lastVisitAt DESC NULLS LAST
            """)
    Page<SalonCustomer> search(@org.springframework.data.repository.query.Param("salonId") UUID salonId,
                                @org.springframework.data.repository.query.Param("q") String q,
                                Pageable pageable);

    /** The "regulars" list when the search box is empty. */
    Page<SalonCustomer> findBySalonIdOrderByLastVisitAtDesc(UUID salonId, Pageable pageable);
}
