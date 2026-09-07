package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.dto.StylistAvailabilityDtos.*;
import com.bmp.salon.services.StylistAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Session 18 — editing a stylist's availability.
 *
 * <p>The free-slot algorithm has read {@code stylist_availability} since Session 8, but nothing
 * could write to it. A stylist with an empty template has no working windows and therefore no
 * bookable slots, so until now every stylist was effectively unbookable and the only control
 * anyone had was the all-or-nothing {@code is_available_today} flag.
 *
 * <h2>Authorization</h2>
 * Owner or manager of THIS salon. Two checks, both needed: the role proves you're staff
 * somewhere, {@code requireSalonScope} proves it's here. Availability decides who can be booked
 * and when — it isn't something a manager at another salon should be able to touch.
 *
 * <p>Stylists cannot EDIT their own hours — that is the salon's roster, not theirs. But since
 * Session 66 they can READ them: see {@code StylistSelfHoursController}. Darshan, on setting a
 * stylist to 10am-7pm and finding nothing on their side: "your taking working hours and stylist
 * dashboard doesnt have reflect on it". The person expected at the chair could not see the shift
 * they were expected for, which is not a defensible thing for a rota to do.
 *
 * <h2>409 responses</h2>
 * A write that would strand existing appointments is refused with a 409 naming them. Pass
 * {@code force: true} to override — a salon that has decided to close early and will phone
 * those customers is a real case, but it has to be deliberate.
 */
@Tag(name = "Stylist availability", description = "Weekly working hours, breaks, one-off date overrides and time off. This is the data the free-slot algorithm consumes — a stylist with no working hours has no bookable slots.")
@RestController
@RequestMapping("/api/v1/salons/{salonId}/stylists/{stylistId}/availability")
public class StylistAvailabilityController {

    private final StylistAvailabilityService service;

    public StylistAvailabilityController(StylistAvailabilityService service) {
        this.service = service;
    }

    @Operation(
        summary = "Get the weekly template",
        description = "Always returns all seven days, with empty lists for days the stylist doesn't work. A UI rendering a week must never have to guess whether a missing day means 'not loaded' or 'day off'.")
    @GetMapping("/weekly")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public WeeklyTemplateResponse getWeekly(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                             @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSalonScope(salonId, caller);
        return service.getWeeklyTemplate(salonId, stylistId);
    }

    @Operation(
        summary = "Replace the weekly template for the days sent",
        description = """
            Only the days present in the request are touched — send Monday alone and the rest of \
            the week is untouched, so a UI editing one day can't clobber a concurrent change.

            An EMPTY `working` list for a day is a real instruction ("doesn't work that day"), \
            which is different from omitting the day entirely.

            Validated for: end-after-start, no overlapping working windows, and breaks sitting \
            inside a working window. Returns 409 if the change would leave existing appointments \
            in the next 4 weeks outside the new hours — pass `force: true` to do it anyway.""")
    @PutMapping("/weekly")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public WeeklyTemplateResponse replaceWeekly(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                                 @Valid @RequestBody WeeklyTemplateRequest req,
                                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSalonScope(salonId, caller);
        return service.replaceWeeklyTemplate(salonId, stylistId, req);
    }

    @Operation(summary = "Dated rules in a window", description = "Leave and one-off overrides between `from` and `to` — the stylist's 'what's coming up'.")
    @GetMapping("/dates")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public List<AvailabilityRuleResponse> listDated(
            @PathVariable UUID salonId, @PathVariable UUID stylistId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSalonScope(salonId, caller);
        return service.listDatedRules(salonId, stylistId, from, to);
    }

    @Operation(
        summary = "Book time off",
        description = "Whole day (omit both times) or part of one. Stored as a `leave` rule, which overrides the weekly template and any date override for that date. Returns 409 if the stylist already has appointments in that time — the classic way a salon double-books itself — unless `force: true`.")
    @PostMapping("/time-off")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public ResponseEntity<AvailabilityRuleResponse> addTimeOff(
            @PathVariable UUID salonId, @PathVariable UUID stylistId,
            @Valid @RequestBody TimeOffRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSalonScope(salonId, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addTimeOff(salonId, stylistId, req));
    }

    @Operation(
        summary = "Different hours on one date",
        description = "\"In late on Thursday\" — an `exception` rule that REPLACES the weekly template for that date only, without meaning the stylist is off. Setting it twice replaces the previous override rather than stacking two sets of hours.")
    @PutMapping("/date-override")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public List<AvailabilityRuleResponse> setDateOverride(
            @PathVariable UUID salonId, @PathVariable UUID stylistId,
            @Valid @RequestBody DateOverrideRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSalonScope(salonId, caller);
        return service.setDateOverride(salonId, stylistId, req);
    }

    @Operation(summary = "Delete a dated rule", description = "Cancel a leave day or drop a date override. Salon-scoped lookup, so a rule id from another salon is a 404.")
    @DeleteMapping("/rules/{ruleId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID salonId, @PathVariable UUID stylistId,
                                            @PathVariable UUID ruleId,
                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSalonScope(salonId, caller);
        service.deleteRule(salonId, ruleId);
        return ResponseEntity.noContent().build();
    }

    /** Staff of THIS salon. The salonId claim is re-resolved on every token mint, so it's live. */
    private void requireSalonScope(UUID salonId, AuthenticatedUser caller) {
        if (caller == null || !salonId.equals(caller.salonId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_STAFF_OF_THIS_SALON");
        }
    }
}
