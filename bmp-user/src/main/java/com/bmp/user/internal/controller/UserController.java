package com.bmp.user.internal.controller;

import com.bmp.user.internal.dto.UserDtos.*;
import com.bmp.user.internal.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** BMP-22: user_schema.users + user_roles CRUD. Open internal API — auth enforced later in Phase 3 (BMP-32). */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable UUID userId) {
        return service.getById(userId);
    }

    @GetMapping
    public UserResponse getByPhone(@RequestParam String phone) {
        return service.getByPhone(phone);
    }

    @PutMapping("/{userId}")
    public UserResponse update(@PathVariable UUID userId, @RequestBody UpdateUserRequest req) {
        return service.update(userId, req);
    }

    @PostMapping("/{userId}/roles")
    public ResponseEntity<RoleResponse> addRole(@PathVariable UUID userId, @Valid @RequestBody CreateRoleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addRole(userId, req));
    }

    @GetMapping("/{userId}/roles")
    public List<RoleResponse> listRoles(@PathVariable UUID userId) {
        return service.listRoles(userId);
    }
}
