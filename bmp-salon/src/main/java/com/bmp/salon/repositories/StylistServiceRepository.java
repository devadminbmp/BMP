package com.bmp.salon.repositories;

import com.bmp.salon.entities.StylistService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StylistServiceRepository extends JpaRepository<StylistService, UUID> {
    List<StylistService> findByStylistIdAndSalonId(UUID stylistId, UUID salonId);

    /**
     * Session 66 — the duplicate check. One row per (stylist, salon, service): a second row for
     * the same pairing has no meaning, and whichever one a reader picks first is arbitrary, so
     * "Ravi does colour" would silently have two different durations depending on query order.
     *
     * <p>Enforced in the service rather than by a unique index because the table predates this
     * rule and may already hold duplicates from the unvalidated {@code addService} — adding a
     * unique constraint now would fail the migration on any salon that hit that path. Session 66
     * refuses new ones; a later migration can dedupe and constrain once the data is known clean.
     */
    Optional<StylistService> findByStylistIdAndSalonIdAndServiceId(UUID stylistId, UUID salonId,
                                                                    UUID serviceId);

    /**
     * Session 66 — every specialisation a stylist has, across salons.
     *
     * <p>Used by the stylist's OWN view, which has no salon-scoped token: a stylist's JWT never
     * carries a salonId (bmp-auth's resolveSalonScope excludes them deliberately), so the
     * salon-scoped finder above is unreachable from their side. In practice V021 means at most
     * one active salon, but past salons keep their rows, so the caller filters by the salon the
     * stylist is actually at rather than assuming this returns one salon's worth.
     */
    List<StylistService> findByStylistId(UUID stylistId);
}
