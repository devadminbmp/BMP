package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.entities.StylistLeaveRequest;
import com.bmp.salon.services.StylistLeaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Leave: requested by a stylist, decided by the salon. V023 (Session 49).
 *
 * <h2>Two roots, on purpose</h2>
 * <ul>
 *   <li>{@code /api/v1/stylist-profile/leave} — the stylist's own. Scoped entirely from the
 *       token; no stylist id appears in any path or body, so there is nothing to swap for a
 *       colleague's.</li>
 *   <li>{@code /api/v1/salons/{salonId}/leave-requests} — the salon's inbox, guarded on the
 *       caller's own salonId the same way the join-request inbox is.</li>
 * </ul>
 *
 * <p>Note the stylist root is {@code /stylist-profile/...} and not {@code /stylists/...}:
 * {@code /api/v1/stylists/*} is in this service's PUBLIC paths, and in Ant a single {@code *}
 * matches one segment, so a stylist-shaped path there would have the JWT filter skipped entirely.
 * That collision has bitten this codebase twice; see StylistSelfController's header.
 *
 * <h2>Approving is what blocks the calendar</h2>
 * A pending request changes nothing. Approval writes {@code stylist_availability} rows, which the
 * booking algorithm has subtracted since V003 — so leave approved a month early takes effect on
 * the day with no scheduled job involved.
 */
@Tag(name = "Stylist leave")
@RestController
public class StylistLeaveController {

    private final StylistLeaveService service;

    public StylistLeaveController(StylistLeaveService service) {
        this.service = service;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────────────────────

    /**
     * @param endsOn    inclusive. Same day as {@code startsOn} for a single day off.
     * @param startTime null for a whole day, which is what leave usually means. Both times must
     *                  be given together — one alone is meaningless.
     */
    public record LeaveRequestBody(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn,
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            @Size(max = 20) String leaveType,
            @Size(max = 500) String reason) {}

    public record LeaveDecisionBody(boolean approve, @Size(max = 500) String note) {}

    /**
     * @param days how many calendar days this covers, computed server-side. The client would
     *             otherwise have to redo inclusive-range arithmetic, and off-by-one on a date
     *             range is the single easiest mistake to make in this whole feature.
     */
    public record LeaveResponse(
            UUID id, UUID stylistId, UUID salonId,
            LocalDate startsOn, LocalDate endsOn, long days,
            LocalTime startTime, LocalTime endTime, boolean wholeDay,
            String leaveType, String reason,
            String status, String decisionNote, Instant decidedAt, Instant createdAt) {}

    // ── the stylist's side ───────────────────────────────────────────────────────────────────

    @Operation(
        summary = "Ask for leave",
        description = "The salon decides. Nothing is blocked until they approve — a pending "
            + "request must not quietly cost the salon a day of bookings.")
    @PreAuthorize("hasRole('STYLIST')")
    @PostMapping("/api/v1/stylist-profile/leave")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveResponse request(@Valid @RequestBody LeaveRequestBody body,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return toResponse(service.request(caller.userId(), body.startsOn(), body.endsOn(),
                body.startTime(), body.endTime(), body.leaveType(), body.reason()));
    }

    @Operation(summary = "My leave", description = "Everything I've asked for, newest first.")
    @PreAuthorize("hasRole('STYLIST')")
    @GetMapping("/api/v1/stylist-profile/leave")
    public List<LeaveResponse> myLeave(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.myLeave(caller.userId()).stream().map(StylistLeaveController::toResponse).toList();
    }

    @Operation(
        summary = "Withdraw my leave",
        description = "Works before AND after approval — plans change, and handing a slot back "
            + "is better than leaving the salon short for a day you're actually there. "
            + "Withdrawing approved leave makes you bookable again immediately.")
    @PreAuthorize("hasRole('STYLIST')")
    @DeleteMapping("/api/v1/stylist-profile/leave/{requestId}")
    public LeaveResponse withdraw(@PathVariable UUID requestId,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return toResponse(service.withdraw(caller.userId(), requestId));
    }

    // ── the salon's side ─────────────────────────────────────────────────────────────────────

    @Operation(
        summary = "Leave requests for my salon",
        description = "Soonest first — leave next week needs an answer before leave next month. "
            + "Owner or manager: staffing the floor is day-to-day work.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @GetMapping("/api/v1/salons/{salonId}/leave-requests")
    public List<LeaveResponse> forSalon(@PathVariable UUID salonId,
                                         @RequestParam(defaultValue = "true") boolean pendingOnly) {
        var rows = pendingOnly ? service.pendingFor(salonId) : service.allFor(salonId);
        return rows.stream().map(StylistLeaveController::toResponse).toList();
    }

    @Operation(
        summary = "Approve or decline leave",
        description = "APPROVING is what takes the stylist off the calendar for those dates — it "
            + "writes the blocking availability rows the booking algorithm reads. A decline "
            + "REQUIRES a reason.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping("/api/v1/salons/{salonId}/leave-requests/{requestId}/decide")
    public LeaveResponse decide(@PathVariable UUID salonId,
                                 @PathVariable UUID requestId,
                                 @Valid @RequestBody LeaveDecisionBody body,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return toResponse(service.decide(salonId, requestId, body.approve(),
                caller == null ? null : caller.userId(), body.note()));
    }

    @Operation(
        summary = "Revoke leave I approved",
        description = "Removes the blocking rows, so the stylist becomes bookable again. Use "
            + "when the approval was a mistake or circumstances changed.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @DeleteMapping("/api/v1/salons/{salonId}/leave-requests/{requestId}")
    public LeaveResponse revoke(@PathVariable UUID salonId, @PathVariable UUID requestId) {
        return toResponse(service.revoke(salonId, requestId));
    }

    // ── mapping ──────────────────────────────────────────────────────────────────────────────

    private static LeaveResponse toResponse(StylistLeaveRequest r) {
        return new LeaveResponse(r.getId(), r.getStylistId(), r.getSalonId(),
                r.getStartsOn(), r.getEndsOn(), r.days(),
                r.getStartTime(), r.getEndTime(), r.isWholeDay(),
                r.getLeaveType(), r.getReason(),
                r.getStatus(), r.getDecisionNote(), r.getDecidedAt(), r.getCreatedAt());
    }
}
