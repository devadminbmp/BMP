package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.dto.StylistAvailabilityDtos.WeeklyTemplateResponse;
import com.bmp.salon.entities.Stylist;
import com.bmp.salon.entities.StylistSalon;
import com.bmp.salon.services.StylistAvailabilityService;
import com.bmp.salon.services.StylistSelfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * A stylist reading their OWN working hours. Session 66.
 *
 * <h2>The gap, in Darshan's words</h2>
 * <blockquote>"your taking working hours and stylish dashboard doesnt have reflect on it"</blockquote>
 *
 * He set Darshan-the-stylist to 10:00–19:00, seven days a week, from the owner's side. Then he
 * opened the stylist's own dashboard and there was nothing about hours anywhere on it.
 *
 * <p>The cause was an access boundary that had been drawn one notch too tight. Every method on
 * {@code StylistAvailabilityController} is {@code hasAnyRole('SALON_OWNER','MANAGER')} plus a
 * salon-scope check — correct for WRITES, since the roster is the salon's to set. But it also
 * gated the READ, and its own class comment recorded that as a known gap: <i>"Stylists cannot yet
 * edit their own hours... Flagged, not silently skipped."</i>
 *
 * <p>That flag was about editing. Reading got caught in the same net by accident, and the result
 * is a rota the person expected to turn up for cannot see. A shift you are not allowed to know
 * about is not a shift, it is a surprise.
 *
 * <h2>Why this is a separate controller rather than a relaxed guard over there</h2>
 * The salon-scoped controller identifies the stylist from a PATH VARIABLE. Relaxing its guard to
 * admit stylists would mean adding "...or you are this stylist" to six annotations, and the two
 * that follow it would be write endpoints — one careless copy of that expression onto
 * {@code PUT /weekly} and a stylist can give themselves a fourteen-hour day.
 *
 * <p>Here there is no path variable to check: the stylist is the token, the salon is derived from
 * their active link, and there is no write method on the class to mis-guard. The boundary is
 * enforced by what this class CAN express, not by remembering to annotate correctly — which is
 * the difference between a rule and a habit.
 *
 * <h2>READ ONLY, permanently</h2>
 * Not "not yet". A stylist setting their own working hours would let them open bookable time the
 * salon has not staffed, and close time the salon has. Changing a shift is a conversation with
 * the salon; the app's job is to show the stylist what was agreed, and to let them ask for time
 * off through the leave flow (V023), which the salon approves.
 */
@Tag(name = "Stylist availability",
     description = "A stylist's own hours, read-only. Setting them is the salon's job — see the salon-scoped endpoints.")
@RestController
public class StylistSelfHoursController {

    private final StylistAvailabilityService availability;
    private final StylistSelfService self;

    public StylistSelfHoursController(StylistAvailabilityService availability, StylistSelfService self) {
        this.availability = availability;
        this.self = self;
    }

    /**
     * My weekly working hours at the salon I currently work at.
     *
     * <p>Always seven days, with empty lists for days off — the same contract the salon-side
     * endpoint offers, and for the same reason: a UI rendering a week must never have to guess
     * whether a missing day means "not loaded" or "not working".
     *
     * <p>404 when they are on no team, which is honest rather than convenient: there is no salon,
     * so there are no hours, and an empty seven-day template would render as "you work nowhere,
     * every day" — a screen that looks like a rota saying nothing.
     */
    @Operation(
        summary = "My working hours",
        description = "Read-only. The salon sets these; a stylist asks for changes through time off, or by talking to them. 404 when not on a team.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/api/v1/stylist-profile/hours")
    public WeeklyTemplateResponse myHours(@AuthenticationPrincipal AuthenticatedUser caller) {
        Stylist me = self.myProfile(caller.userId());
        Optional<StylistSalon> link = self.activeLink(me.getId());
        if (link.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "NO_SALON: you're not on a salon's team, so there are no working hours to show.");
        }
        return availability.getWeeklyTemplate(link.get().getSalonId(), me.getId());
    }

    /**
     * Days the salon has marked me off, or changed my hours for. Session 66.
     *
     * <h2>The half of "time off" a stylist could not see</h2>
     * Darshan: "next leaves of stylish". There are TWO ways a stylist ends up not working a day,
     * and only one of them reached them:
     *
     * <ol>
     *   <li><b>They asked.</b> A leave request (V023), which the salon approves. Visible on their
     *       Time off tab since Session 49.</li>
     *   <li><b>The salon decided.</b> An owner opens the availability sheet, hits "Time off", and
     *       writes a {@code leave} rule directly. <b>Nothing told the stylist.</b> The rule blocks
     *       their bookings, so their day silently empties, and the only signal they get is an
     *       oddly quiet Tuesday.</li>
     * </ol>
     *
     * <p>The second case is the one this endpoint exists for. It is also the more important one:
     * a stylist who asked for a day off knows they asked. A stylist the salon has stood down has
     * been told nothing, and may well turn up.
     *
     * <p>Approved requests write {@code leave} rules too, so both kinds come back here. The client
     * cross-references the stylist's own request list to label which is which — done there rather
     * than here because the request list is already loaded on that screen, and matching on dates
     * in one place beats returning a "source" field this table cannot honestly populate (the rows
     * carry no record of who created them).
     *
     * <p>Forward-looking by default. Time off that has already passed is history, and a stylist
     * checking "am I working next week" should not have to scroll through last month first.
     */
    @Operation(
        summary = "Days I'm off, or on different hours",
        description = "Read-only. Includes both time off the salon set directly and leave they approved. Defaults to the next 60 days. Empty when not on a team.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/api/v1/stylist-profile/dated-hours")
    public java.util.List<com.bmp.salon.dto.StylistAvailabilityDtos.AvailabilityRuleResponse> myDatedRules(
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.time.LocalDate from,
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.time.LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        Stylist me = self.myProfile(caller.userId());
        Optional<StylistSalon> link = self.activeLink(me.getId());
        // Empty rather than 404: this renders as a SECTION on a screen that has other content, so
        // an error would blank a tab that still has the stylist's own requests to show.
        if (link.isEmpty()) return java.util.List.of();

        java.time.LocalDate start = from != null ? from : java.time.LocalDate.now(com.bmp.common.time.BmpTimeZone.ZONE);
        java.time.LocalDate end = to != null ? to : start.plusDays(60);
        return availability.listDatedRules(link.get().getSalonId(), me.getId(), start, end);
    }
}
