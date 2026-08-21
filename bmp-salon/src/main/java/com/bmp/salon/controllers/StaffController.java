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

import java.util.List;
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
        summary = "Issue a manager or stylist invite",
        description = """
            Generates a one-time token, valid 48h, locked to the invitee's phone. Share it \
            out-of-band (SMS/WhatsApp — not auto-sent yet); the invitee passes it as \
            `inviteToken` on /otp/verify with the matching role.

            `role` defaults to `manager`. Two different things happen on redemption:
            · manager → a salon_staff seat (dashboard access, salon-scoped JWT)
            · stylist → a stylist_salon link (portable identity; the stylist's JWT is NOT \
            salon-scoped, because they can work at several salons and their profile travels \
            with them)

            Authorization differs accordingly: only the OWNER can create managers (a manager \
            who could mint managers is a privilege-escalation path), while owners AND managers \
            can invite stylists, because staffing the floor is day-to-day work.""")
    @PostMapping("/{salonId}/invites")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public ResponseEntity<InviteResponse> createInvite(
            @PathVariable UUID salonId,
            @Valid @RequestBody CreateInviteRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireStaffOfSalon(salonId, caller);
        // A manager must not be able to create another manager: that's how one compromised
        // manager account becomes permanent, self-replicating access to a salon.
        if ("manager".equals(req.roleOrDefault()) && !"salon_owner".equalsIgnoreCase(caller.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ONLY_OWNER_CAN_INVITE_MANAGERS");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createInvite(salonId, req));
    }

    // ---- Session 15: owner-facing team management -------------------------------------
    // Every endpoint below is owner-only AND salon-scoped: hasRole('SALON_OWNER') proves you
    // are AN owner, requireOwnerOfSalon proves you are THIS salon's owner. The second check is
    // the one that stops a legitimate owner from managing somebody else's staff.

    @Operation(
        summary = "List this salon's staff — owner only",
        description = "Owner + manager seats, enriched with name/phone from bmp-user. Enrichment is best-effort: if bmp-user is unreachable those fields come back null rather than failing the whole roster. `removable` is false for OWNER seats, `isSelf` marks the caller's own.")
    @GetMapping("/{salonId}/staff")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public List<StaffMemberResponse> listStaff(
            @PathVariable UUID salonId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOwnerOfSalon(salonId, caller);
        return service.listStaff(salonId, caller.userId());
    }

    @Operation(
        summary = "Add an existing BMP user as manager, by phone — owner only",
        description = "For people who ALREADY have a BMP account: invites don't work for them, because /otp/verify ignores role+inviteToken once a phone exists (it's a login at that point). 404 if no account exists for the phone — send an invite instead. Also switches their default role to `manager`, without which their JWT would still claim `customer`. Takes effect on their next token refresh (~15 min) or re-login.")
    @PostMapping("/{salonId}/staff")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ResponseEntity<StaffMemberResponse> addManager(
            @PathVariable UUID salonId,
            @Valid @RequestBody AddStaffRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOwnerOfSalon(salonId, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addManager(salonId, req, caller.userId()));
    }

    @Operation(
        summary = "Remove a staff member — owner only",
        description = "Deletes the salon_staff seat and reverses the role grant in bmp-user (default role back to `customer`, then revoke — that order is forced, bmp-user 409s on revoking a still-default role). 409 for OWNER seats (transfer ownership first) and for removing yourself. Their current access token stays valid until it expires (~15 min); the next refresh mints a customer token.")
    @DeleteMapping("/{salonId}/staff/{staffId}")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ResponseEntity<Void> removeStaff(
            @PathVariable UUID salonId,
            @PathVariable UUID staffId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOwnerOfSalon(salonId, caller);
        service.removeStaff(salonId, staffId, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "List pending invites",
        description = "Codes issued but not yet redeemed, newest first, optionally filtered by `role` (manager|stylist). Expired-but-unredeemed invites still show as pending — `expiresAt` says which are dead. Managers see only stylist invites; the manager roster is the owner's business.")
    @GetMapping("/{salonId}/invites")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public List<InviteResponse> listInvites(
            @PathVariable UUID salonId,
            @RequestParam(required = false) String role,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireStaffOfSalon(salonId, caller);
        boolean isOwner = "salon_owner".equalsIgnoreCase(caller.role());
        // Force the filter for managers rather than 403-ing: they get the list they're
        // entitled to, which is more useful than a refusal.
        String effectiveRole = isOwner ? role : "stylist";
        return service.listPendingInvites(salonId, effectiveRole);
    }

    @Operation(summary = "Revoke a pending invite", description = "Marks it `revoked` rather than deleting it: consumeInvite only matches `pending`, so the token dies immediately while the audit trail survives. 409 if it was already accepted or revoked. A manager can revoke stylist invites only.")
    @DeleteMapping("/{salonId}/invites/{inviteId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    public ResponseEntity<Void> revokeInvite(
            @PathVariable UUID salonId,
            @PathVariable UUID inviteId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireStaffOfSalon(salonId, caller);
        service.revokeInvite(salonId, inviteId, caller.userId(),
                "salon_owner".equalsIgnoreCase(caller.role()));
        return ResponseEntity.noContent().build();
    }

    // ---- internal (service-to-service) --------------------------------------------------

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

    /**
     * Session 17: any staff member (owner OR manager) of THIS salon. Used where the action is
     * operational rather than administrative — inviting a stylist, for instance. The salonId in
     * the JWT is re-resolved from salon_staff on every token mint, so it's a live fact, not a
     * claim the client can hold onto after being removed.
     */
    private void requireStaffOfSalon(UUID salonId, AuthenticatedUser caller) {
        if (caller == null || !salonId.equals(caller.salonId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_STAFF_OF_THIS_SALON");
        }
    }

    private void requireOwnerOfSalon(UUID salonId, AuthenticatedUser caller) {
        if (caller == null || !salonId.equals(caller.salonId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_OWNER_OF_THIS_SALON");
        }
    }
}
