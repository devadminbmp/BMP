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

    @Operation(summary = "[internal] Look up a user by email",
               description = "Service-only, same reasoning as the phone lookup: arbitrary email\u2192profile resolution is an enumeration risk. Session 65 \u2014 added so a salon can invite a stylist by email as well as by phone.")
    @GetMapping("/by-email")
    @PreAuthorize("hasRole('SERVICE')")
    public UserResponse getByEmail(@RequestParam String email) {
        return service.getByEmail(email);
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

    /**
     * Erase a user's personal data. {@code ROLE_SERVICE} only. Session 56.
     *
     * <h2>Why no human role may call this</h2>
     * It is irreversible and it destroys data. The legitimate path is a data-deletion request in
     * the admin console, which verifies the request came from the account holder, records who
     * actioned it and when, and then calls this. A staff member with a URL should not be able to
     * erase somebody on a whim, and a customer should not be able to erase somebody else at all.
     *
     * <p>Idempotent — a retried compliance job gets 200, not a 409.
     */
    @Operation(summary = "[internal] Anonymise an account (irreversible)",
               description = "SERVICE only, called by bmp-admin when a verified deletion request is actioned. Clears name, email, gender, age, photo and hair profile, and replaces the phone with a non-dialable tombstone that frees the real number for future signup. The id survives so past bookings and invoices still resolve. The account can never be reactivated afterwards.")
    @PostMapping("/{userId}/anonymise")
    @PreAuthorize("hasRole('SERVICE')")
    public UserResponse anonymise(@PathVariable UUID userId,
                                   @RequestParam(required = false) String reason) {
        return service.anonymise(userId, reason);
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
    /** Change phone and/or email. Null leaves a field unchanged. */
    public record ChangeContactRequest(String phone, String email) {}

    /**
     * Administered contact change. Session 65.
     *
     * <p>ROLE_SERVICE only — bmp-admin is the caller, because that is where the staff member is
     * authenticated, where AccountScope decides whether their role may touch THIS account, and
     * where the audit entry is written. bmp-user knows nothing about staff roles and should not
     * start learning: it owns the users table, not the question of who may change it.
     */
    @Operation(summary = "[internal] Change a user's phone and/or email",
               description = "Called by bmp-admin after an authority check. Changing the phone changes who can log in.")
    @PatchMapping("/{userId}/contact")
    @PreAuthorize("hasRole('SERVICE')")
    public UserResponse changeContact(@PathVariable UUID userId,
                                       @RequestBody ChangeContactRequest req) {
        return service.changeContact(userId, req.phone(), req.email());
    }

    /**
     * @param staffId who is doing it, for the row. bmp-user takes this on trust from bmp-admin
     *                because only bmp-admin can authenticate a staff member — and the call is
     *                ROLE_SERVICE, so nothing outside the mesh can reach it.
     */
    public record BlockRequest(UUID staffId, String reason) {}

    /**
     * Block sign-in. Session 65.
     *
     * <h2>Separate from /deactivate, and it has to be</h2>
     * {@code /deactivate} is the person's own "I want a break", and bmp-auth undoes it on their
     * next login. Routing a staff block through it produced a block that lifted itself. This
     * endpoint writes {@code blocked_at}, which nothing reverses automatically.
     */
    @Operation(summary = "[internal] Block an account",
               description = "Reversible via /unblock. Unlike /deactivate, a successful login does NOT clear it.")
    @PostMapping("/{userId}/block")
    @PreAuthorize("hasRole('SERVICE')")
    public UserResponse block(@PathVariable UUID userId, @RequestBody BlockRequest req) {
        return service.block(userId, req.staffId(), req.reason());
    }

    @Operation(summary = "[internal] Lift a block",
               description = "Does not reactivate a self-deactivated account — that stays the person's own decision.")
    @PostMapping("/{userId}/unblock")
    @PreAuthorize("hasRole('SERVICE')")
    public UserResponse unblock(@PathVariable UUID userId) {
        return service.unblock(userId);
    }
}
