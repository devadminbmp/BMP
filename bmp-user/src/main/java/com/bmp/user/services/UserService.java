package com.bmp.user.services;

import com.bmp.user.dto.UserDtos.*;
import com.bmp.user.entities.UserRoles;
import com.bmp.user.entities.Users;
import com.bmp.user.repositories.UserRolesRepository;
import com.bmp.user.repositories.UsersRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** BMP-22: plain CRUD on user_schema.users/user_roles. No OTP/auth middleware here — that's bmp-auth-service (Phase 3). */
@Service
public class UserService {

    private final UsersRepository users;
    private final UserRolesRepository roles;

    public UserService(UsersRepository users, UserRolesRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        if (users.existsByPhone(req.phone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS");
        }
        Users u = new Users(req.phone(), req.name(), req.gender(),
                req.age() == null ? 0 : req.age(), req.email(), null, null, null,
                req.defaultRole(), false);
        u = users.save(u);
        return toResponse(u);
    }

    public UserResponse getById(UUID id) {
        return users.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    public UserResponse getByPhone(String phone) {
        return users.findByPhone(phone).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest req) {
        Users u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (req.name() != null) u.setName(req.name());
        if (req.gender() != null) u.setGender(req.gender());
        if (req.age() != null) u.setAge(req.age());
        if (req.email() != null) u.setEmail(req.email());
        if (req.profilePhotoUrl() != null) u.setProfilePhotoUrl(req.profilePhotoUrl());
        if (req.hairType() != null) u.setHairType(req.hairType());
        if (req.hairLength() != null) u.setHairLength(req.hairLength());
        u.touch();
        return toResponse(u); // managed entity, flushed on commit
    }

    @Transactional
    public RoleResponse addRole(UUID userId, CreateRoleRequest req) {
        users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        UserRoles r = new UserRoles(userId, req.role(), req.salonId());
        r = roles.save(r);
        return new RoleResponse(r.getId(), r.getUserId(), r.getRole(), r.getSalonId());
    }

    public List<RoleResponse> listRoles(UUID userId) {
        return roles.findByUserId(userId).stream()
                .map(r -> new RoleResponse(r.getId(), r.getUserId(), r.getRole(), r.getSalonId()))
                .toList();
    }

    private UserResponse toResponse(Users u) {
        return new UserResponse(u.getId(), u.getPhone(), u.getName(), u.getGender(), u.getAge(),
                u.getEmail(), u.getProfilePhotoUrl(), u.getHairType(), u.getHairLength(),
                u.getDefaultRole(), u.isVerified(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
