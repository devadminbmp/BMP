package com.bmp.salon.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Calls bmp-user-service by its Eureka-registered name, with the internal shared-secret
 * header (FeignInternalKeyConfig) so bmp-user's {@code hasRole('SERVICE')} guards pass.
 *
 * <p><b>Why bmp-salon needs this (Session 15):</b> a salon's staff roster is two facts living
 * in two services. <em>Who has a seat</em> is ours ({@code salon_schema.salon_staff}); <em>who
 * that person is</em> (name, phone) and <em>what role their JWT will claim</em>
 * ({@code user_schema.users.default_role} + {@code user_roles}) belong to bmp-user. Owner-facing
 * team management has to touch both, or you get seats belonging to unnamed UUIDs and managers
 * whose tokens still say "customer".
 *
 * <p>bmp-user's own Javadoc already anticipates us: its revoke-role endpoint is documented
 * "Service-only (e.g. bmp-salon removing a manager's seat)".
 *
 * <p>Records here are LOCAL MIRRORS of bmp-user's DTOs, deliberately partial — we map only the
 * fields we use, so an unrelated field being added over there can't break this service.
 */
@FeignClient(name = "bmp-user-service", configuration = com.bmp.salon.config.FeignInternalKeyConfig.class)
public interface UserServiceClient {

    /** Mirror of bmp-user's UserResponse — partial on purpose (see class Javadoc). */
    record UserDto(
        UUID id, String phone, String name, String gender, Integer age, String email,
        String profilePhotoUrl, String hairType, String hairLength, String defaultRole,
        boolean isVerified, Instant deactivatedAt, Instant createdAt, Instant updatedAt
    ) {}

    record RoleDto(UUID id, UUID userId, String role, UUID salonId) {}

    record CreateRoleRequest(String role, UUID salonId) {}

    record DefaultRoleRequest(String defaultRole) {}

    @GetMapping("/api/v1/users/{userId}")
    ResponseEntity<UserDto> getUserById(@PathVariable("userId") UUID userId);

    /** E.164. Service-only over there — arbitrary phone→profile lookup is an enumeration risk. */
    @GetMapping("/api/v1/users")
    ResponseEntity<UserDto> getUserByPhone(@RequestParam("phone") String phone);

    @GetMapping("/api/v1/users/{userId}/roles")
    List<RoleDto> listRoles(@PathVariable("userId") UUID userId);

    @PostMapping("/api/v1/users/{userId}/roles")
    RoleDto addRole(@PathVariable("userId") UUID userId, @RequestBody CreateRoleRequest req);

    /**
     * 409 if the role being revoked is still the user's default — bmp-user refuses to leave a
     * user whose next token would claim a role they no longer hold. So callers must switch the
     * default FIRST. See StaffService.removeStaff.
     */
    @DeleteMapping("/api/v1/users/{userId}/roles/{roleId}")
    ResponseEntity<Void> removeRole(@PathVariable("userId") UUID userId, @PathVariable("roleId") UUID roleId);

    @PutMapping("/api/v1/users/{userId}/default-role")
    UserDto setDefaultRole(@PathVariable("userId") UUID userId, @RequestBody DefaultRoleRequest req);
}
