package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * Reads salon records for the moderation queue and salon-side support.
 *
 * <p>Partial mirrors again — the console needs a name, an area and a staff count, not
 * bmp-salon's full model. Anything richer becomes a coupling that breaks on their next change.
 */
@FeignClient(name = "bmp-salon-service", configuration = com.bmp.admin.config.FeignInternalKeyConfig.class)
public interface SalonServiceClient {

    record SalonDto(UUID id, String name, Object location, String stylistAssignmentStrategy) {}

    record StylistSalonDto(
        UUID id, UUID stylistId, String stylistName, UUID salonId, String status,
        boolean isAvailableToday
    ) {}

    @GetMapping("/api/v1/salons/{salonId}")
    ResponseEntity<SalonDto> getSalon(@PathVariable("salonId") UUID salonId);

    /**
     * The salon's stylists.
     *
     * <p>Used to answer the most common salon complaint — "we're not getting any bookings" —
     * which is almost always nobody being marked available rather than a lack of demand.
     */
    @GetMapping("/api/v1/salons/{salonId}/stylists")
    List<StylistSalonDto> listStylists(@PathVariable("salonId") UUID salonId);

    record StatusChangeRequest(String status) {}

    /**
     * Apply a moderation decision.
     *
     * <p>THIS is what makes a salon visible to customers. The {@code salon_review} row in
     * admin_schema records the decision; this call enacts it. Without it, approving in the
     * console would change a row nobody outside the console reads, and the salon would stay
     * invisible — with the moderator convinced they had approved it.
     */
    @PutMapping("/api/v1/salons/internal/{salonId}/status")
    void setSalonStatus(@PathVariable("salonId") UUID salonId, @RequestBody StatusChangeRequest req);

    record SalonSupportSummary(
        UUID id, String name, String location, String status,
        int stylistCount, int activeStylistsToday, int stylistsWithHours, String configWarning
    ) {}

    /** "Why aren't we getting bookings?" — answered server-side, in one call. */
    @GetMapping("/api/v1/salons/internal/{salonId}/support-summary")
    SalonSupportSummary supportSummary(@PathVariable("salonId") UUID salonId);
}
