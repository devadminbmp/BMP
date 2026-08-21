package com.bmp.salon.repositories;

import com.bmp.salon.entities.SalonCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** V011, Session 40. What each salon does — the filter chips on the discovery screen. */
public interface SalonCategoryRepository extends JpaRepository<SalonCategory, UUID> {

    List<SalonCategory> findBySalonId(UUID salonId);

    /**
     * Categories for MANY salons in one query.
     *
     * <p>The discovery list renders dozens of salons and each needs its categories. Per-salon
     * lookups would be a textbook N+1 on the platform's most-viewed screen — the caller groups
     * these by {@code salonId} once instead.
     */
    List<SalonCategory> findBySalonIdIn(List<UUID> salonIds);

    /**
     * "Salons that do hair colour" — the reason this is a table and not a string.
     *
     * <p>Returns ids rather than entities: the caller already has the salons and only needs to
     * know which survive the filter.
     */
    @Query("SELECT c.salonId FROM SalonCategory c WHERE lower(c.category) = lower(:category)")
    List<UUID> findSalonIdsByCategory(@Param("category") String category);

    void deleteBySalonId(UUID salonId);
}
