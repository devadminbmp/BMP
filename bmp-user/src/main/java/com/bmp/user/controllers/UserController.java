package com.bmp.user.controllers;

import com.bmp.user.dto.UserDtos.*;
import com.bmp.user.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * BMP-22 + Session 13: users / user_roles / onboarding_state, now with a real
 * authorization pass — the first business service after bmp-auth to get one.
 *
 * <p>The model: {@code ROLE_SERVICE} (internal callers like bmp-auth, via
 * X-Internal-Service-Key) can do everything; an end user can only touch THEIR OWN
 * record ({@code principal.userId() == #userId} — principal is
 * {@link com.bmp.common.security.AuthenticatedUser}, and SpEL's {@code or}
 * short-circuits, so the service branch never evaluates the principal accessor on the
 * String principal service calls carry). Phone lookup and user creation are
 * service-only: phone→profile resolution for arbitrary numbers is exactly the kind of
 * enumeration a public endpoint shouldn't offer, and users are only ever created by
 * bmp-auth post-OTP. public-paths in application.yml is tightened to match (swagger +
 * health/info only).
 */
@Tag(name = "Users", description = "users + user_roles + onboarding_state. Self-or-service authorization: end users can only access their own record; creation, phone lookup and role grants are service-to-service only (bmp-auth / bmp-salon flows).")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @Operation(summary = "[internal] Create a user", description = "Called by bmp-auth on first successful OTP verify — 409 if the phone already exists (now DB-enforced, V004). Users are created verified, since every creation path is post-OTP.")
    @PostMapping
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a user by id — self or internal only")
    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public UserResponse getById(@PathVariable UUID userId) {
        return service.getById(userId);
    }

    @Operation(summary = "[internal] Look up a user by phone (E.164)", description = "Service-only: bmp-auth uses it to distinguish signup from login. Not exposed to end users — arbitrary phone→profile resolution is an enumeration/privacy risk.")
    @GetMapping
    @PreAuthorize("hasRole('SERVICE')")
    public UserResponse getByPhone(@RequestParam String phone) {
        return service.getByPhone(phone);
    }

    @Operation(summary = "Update profile fields — self or internal only", description = "name, gender, age, email, photo, hair type/length. Phone is immutable (identity key).")
    @PutMapping("/{userId}")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public UserResponse update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest req) {
        return service.update(userId, req);
    }

    // ---- deactivation ----

    @Operation(summary = "Deactivate own account (soft)", description = "Instagram-style: hides the account, reversed automatically on the next successful OTP login. Not a data deletion. 409 if already deactivated.")
    @PostMapping("/{userId}/deactivate")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public UserResponse deactivate(@PathVariable UUID userId) {
        return service.deactivate(userId);
    }

    @Operation(summary = "[internal] Reactivate a deactivated account", description = "Called by bmp-auth when a deactivated user completes a fresh OTP login — the login IS the reactivation signal. Idempotent.")
    @PostMapping("/{userId}/reactivate")
    @PreAuthorize("hasRole('SERVICE')")
    public UserResponse reactivate(@PathVariable UUID userId) {
        return service.reactivate(userId);
    }

    // ---- roles ----

    @Operation(summary = "[internal] Grant a role to a user", description = "Service-only — roles are granted by real flows (bmp-auth signup, bmp-salon staff/invite consumption), never self-service. 409 if the exact role(+salon) is already held.")
    @PostMapping("/{userId}/roles")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<RoleResponse> addRole(@PathVariable UUID userId, @Valid @RequestBody CreateRoleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addRole(userId, req));
    }

    @Operation(summary = "List every role a user holds — self or internal only")
    @GetMapping("/{userId}/roles")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public List<RoleResponse> listRoles(@PathVariable UUID userId) {
        return service.listRoles(userId);
    }

    @Operation(summary = "[internal] Revoke a role", description = "Service-only (e.g. bmp-salon removing a manager's seat). 409 if it's the user's current default role — switch the default first, or the next token mint would claim a role no longer held.")
    @DeleteMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> removeRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        service.removeRole(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Switch own default role", description = "The \"stylist who also books as a customer\" case — changes which held role gets minted into the JWT on next login/refresh. 409 if the user doesn't actually hold the requested role.")
    @PutMapping("/{userId}/default-role")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public UserResponse setDefaultRole(@PathVariable UUID userId, @Valid @RequestBody DefaultRoleRequest req) {
        return service.setDefaultRole(userId, req);
    }

    // ---- onboarding state (crash-recovery blob) ----

    @Operation(summary = "Save onboarding progress — self only", description = "Wholesale replace on every save (client re-sends the full blob each step). Transient crash-recovery data, not business data.")
    @PutMapping("/{userId}/onboarding-state")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public OnboardingStateResponse saveOnboardingState(@PathVariable UUID userId,
                                                        @Valid @RequestBody OnboardingStateRequest req) {
        return service.saveOnboardingState(userId, req);
    }

    @Operation(summary = "Resume onboarding — self only", description = "404 NO_ONBOARDING_IN_PROGRESS if nothing was saved or it was already completed/cleared.")
    @GetMapping("/{userId}/onboarding-state")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public OnboardingStateResponse getOnboardingState(@PathVariable UUID userId) {
        return service.getOnboardingState(userId);
    }

    @Operation(summary = "Complete onboarding — self only", description = "Deletes the saved blob (per spec: \"deleted when onboarding completes — not a business data table\"). Idempotent.")
    @DeleteMapping("/{userId}/onboarding-state")
    @PreAuthorize("hasRole('SERVICE') or principal.userId() == #userId")
    public ResponseEntity<Void> clearOnboardingState(@PathVariable UUID userId) {
        service.clearOnboardingState(userId);
        return ResponseEntity.noContent().build();
    }
}
