package com.bmp.salon.controllers;

import com.bmp.common.security.AuthenticatedUser;
import com.bmp.salon.dto.StaffDtos.*;
import com.bmp.salon.repositories.SalonStaffRepository;
import com.bmp.salon.services.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Session 6: salon_schema.salon_staff / staff_invites.
 *
 * <p>{@code /internal/**} endpoints here are called by bmp-auth over Feign during the
 * signup/token-mint flow (see AuthService in bmp-auth), never by an end user — they're
 * reachable through api-gateway's existing {@code /api/v1/salons/**} route like everything
 * else in this controller, so they're locked to {@code ROLE_SERVICE} (the internal
 * shared-secret credential JwtAuthFilter grants for the {@code X-Internal-Service-Key}
 * header) rather than relying on the path being "hidden".
 */
@Tag(name = "Staff & Invites", description = "salon_staff (owner/manager seats) + staff_invites (owner-issued manager invite tokens). The /internal/** endpoints are service-to-service only (ROLE_SERVICE), called by bmp-auth — not reachable with a normal user token even though they're technically on the public gateway route.")
@RestController
@RequestMapping("/api/v1/salons")
public class StaffController {

    private final StaffService service;
    private final SalonStaffRepository staffRepo;

    public StaffController(StaffService service, SalonStaffRepository staffRepo) {
        this.service = service;
        this.staffRepo = staffRepo;
    }

    @Operation(
        summary = "Issue a manager invite — owner of this salon only",
        description = "Generates a one-time token, valid 48h. Share it with the invitee out-of-band (SMS/WhatsApp — not auto-sent yet); they pass it as `inviteToken` on /otp/verify with role=MANAGER to complete signup and become this salon's manager.")
    @PostMapping("/{salonId}/invites")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ResponseEntity<InviteResponse> createInvite(
            @PathVariable UUID salonId,
            @Valid @RequestBody CreateInviteRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOwnerOfSalon(salonId, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createInvite(salonId, req));
    }

    @Operation(summary = "[internal] Consume a manager invite", description = "Called by bmp-auth during MANAGER signup, after the user row already exists. Validates phone match + expiry, creates the salon_staff row, marks the invite accepted.")
    @PostMapping("/internal/staff-invites/consume")
    @PreAuthorize("hasRole('SERVICE')")
    public ConsumeInviteResponse consumeInvite(@Valid @RequestBody ConsumeInviteRequest req) {
        return service.consumeInvite(req);
    }

    @Operation(summary = "[internal] Look up a user's current staff seat", description = "Called by bmp-auth on every token mint (login AND refresh) for SALON_OWNER/MANAGER roles, to keep the JWT's salonId claim fresh. 204 means no seat yet.")
    @GetMapping("/internal/staff-lookup")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<StaffLookupResponse> lookupStaff(@RequestParam UUID userId) {
        return service.lookupByUserId(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private void requireOwnerOfSalon(UUID salonId, AuthenticatedUser caller) {
        if (caller == null || !salonId.equals(caller.salonId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_OWNER_OF_THIS_SALON");
        }
    }
}
