package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** V014 (Session 44) — the salon gallery. Always read for one salon, always in display order. */
public interface SalonPhotoRepository extends JpaRepository<SalonPhoto, UUID> {

    /** Ordered by the owner's choice, then creation, so ties are stable rather than arbitrary. */
    List<SalonPhoto> findBySalonIdOrderBySortOrderAscCreatedAtAsc(UUID salonId);

    long countBySalonId(UUID salonId);
}
