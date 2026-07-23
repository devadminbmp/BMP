package com.bmp.user.controllers;

import com.bmp.user.dto.UserDtos.*;
import com.bmp.user.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-22: user_schema.users + user_roles CRUD. Still fully open (no @PreAuthorize) — most
 * callers are other services (bmp-auth's createUser/getUserByPhone/getUserById) over the
 * internal-service-key credential, not end users through the gateway. A real per-endpoint
 * authorization pass (self-only access for a customer, etc.) is a follow-up ticket. */
@Tag(name = "Users", description = "users + user_roles CRUD. Mostly called service-to-service by bmp-auth, not directly by end users.")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @Operation(summary = "Create a user", description = "Called by bmp-auth on first successful OTP verify — 409 if the phone already exists.")
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Get a user by id")
    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable UUID userId) {
        return service.getById(userId);
    }

    @Operation(summary = "Look up a user by phone (E.164)", description = "Used by bmp-auth to check whether a phone number is a new signup or an existing login.")
    @GetMapping
    public UserResponse getByPhone(@RequestParam String phone) {
        return service.getByPhone(phone);
    }

    @Operation(summary = "Update profile fields (name, gender, age, email, photo, hair type/length)")
    @PutMapping("/{userId}")
    public UserResponse update(@PathVariable UUID userId, @RequestBody UpdateUserRequest req) {
        return service.update(userId, req);
    }

    @Operation(summary = "Grant a role to a user", description = "Role is one of CUSTOMER/SALON_OWNER/MANAGER/STYLIST, optionally scoped to a salonId. A user can hold more than one role row.")
    @PostMapping("/{userId}/roles")
    public ResponseEntity<RoleResponse> addRole(@PathVariable UUID userId, @Valid @RequestBody CreateRoleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addRole(userId, req));
    }

    @Operation(summary = "List every role a user holds")
    @GetMapping("/{userId}/roles")
    public List<RoleResponse> listRoles(@PathVariable UUID userId) {
        return service.listRoles(userId);
    }
}
