package com.bmp.salon.controllers;

import com.bmp.salon.entities.Salon;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.repositories.SalonRepository;
import com.bmp.salon.repositories.StylistAvailabilityRepository;
import com.bmp.salon.repositories.StylistSalonRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Internal salon endpoints for the staff console — {@code ROLE_SERVICE} only, called by
 * bmp-admin.
 *
 * <p>Two jobs, both of which the console cannot do any other way:
 *
 * <ol>
 *   <li><b>Flip a salon's visibility</b> when a moderator approves, rejects or suspends it.
 *       Without this the console's approval button changes a row in admin_schema and nothing
 *       else — the salon stays invisible and the moderator has no idea.</li>
 *   <li><b>Answer "why aren't we getting bookings?"</b>, which is the most common salon support
 *       call and almost always a configuration problem rather than a demand problem.</li>
 * </ol>
 */
@Tag(name = "Internal salon (staff console)", description = "Service-to-service: apply a moderation decision, and diagnose a salon that isn't receiving bookings.")
@RestController
@RequestMapping("/api/v1/salons/internal")
@PreAuthorize("hasRole('SERVICE')")
public class InternalSalonController {

    private static final Logger log = LoggerFactory.getLogger(InternalSalonController.class);

    private final SalonRepository salons;
    private final StylistSalonRepository stylistSalons;
    private final StylistAvailabilityRepository availability;

    public InternalSalonController(SalonRepository salons, StylistSalonRepository stylistSalons,
                                    StylistAvailabilityRepository availability) {
        this.salons = salons;
        this.stylistSalons = stylistSalons;
        this.availability = availability;
    }

    public record StatusChangeRequest(
        @NotBlank @Pattern(regexp = "^(pending|approved|rejected|suspended)$") String status
    ) {}

    public record SalonSupportSummary(
        UUID id, String name, String location, String status,
        int stylistCount, int activeStylistsToday, int stylistsWithHours,
        /** The answer to "why aren't we getting bookings", or null if nothing is obviously wrong. */
        String configWarning
    ) {}

    @Operation(
        summary = "Apply a moderation decision",
        description = "Called by bmp-admin when a moderator approves, rejects or suspends a salon. This is what actually makes a salon visible to customers — the console's own row is a record of the decision, not the switch.")
    @PutMapping("/{salonId}/status")
    @Transactional
    public ResponseEntity<Void> setStatus(@PathVariable UUID salonId,
                                           @RequestBody StatusChangeRequest req) {
        Salon salon = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        salon.setStatus(req.status());
        salon.touch();
        salons.save(salon);

        log.info("Salon {} status set to {} by the staff console", salonId, req.status());
        return ResponseEntity.noContent().build();
    }

    /**
     * Why isn't this salon getting bookings?
     *
     * <p>Nearly always one of three things, in this order of frequency:
     * <ol>
     *   <li>No stylist has any working hours set, so the availability algorithm has nothing to
     *       offer. This is by far the most common, and completely invisible from the salon's
     *       side — their profile looks fine.</li>
     *   <li>Everyone is switched off for today.</li>
     *   <li>The salon isn't approved.</li>
     * </ol>
     *
     * <p>Computing this here rather than making an agent click through three screens is the
     * difference between diagnosing it in ten seconds and escalating it to engineering.
     */
    @Operation(summary = "Diagnose a salon", description = "Returns the salon plus the most likely reason it isn't receiving bookings.")
    @GetMapping("/{salonId}/support-summary")
    public SalonSupportSummary supportSummary(@PathVariable UUID salonId) {
        Salon salon = salons.findById(salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND"));

        List<StylistSalon> links = stylistSalons.findBySalonIdAndStatus(salonId, "active");
        int activeToday = (int) links.stream().filter(StylistSalon::isAvailableToday).count();

        // A stylist with no weekly_template rows produces no slots, whatever else is true.
        int withHours = (int) links.stream()
                .filter(l -> !availability
                        .findByStylistIdAndSalonIdAndRuleTypeOrderByDayOfWeekAscStartTimeAsc(
                                l.getStylistId(), salonId, "weekly_template")
                        .isEmpty())
                .count();

        String warning = null;
        if (!"approved".equalsIgnoreCase(salon.getStatus())) {
            warning = "This salon is '" + salon.getStatus() + "' — customers cannot see it at all until it is approved.";
        } else if (links.isEmpty()) {
            warning = "No stylists are linked to this salon, so there is nobody to book with.";
        } else if (withHours == 0) {
            warning = "No stylist has working hours set, so the salon offers no bookable slots. "
                    + "This is the usual cause and it isn't visible from the salon's own screens.";
        } else if (activeToday == 0) {
            warning = "Every stylist is marked unavailable today. Nothing can be booked for today, "
                    + "though future days are unaffected.";
        }

        return new SalonSupportSummary(
                salon.getId(), salon.getName(), salon.getLocation(), salon.getStatus(),
                links.size(), activeToday, withHours, warning);
    }
}
