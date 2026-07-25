package com.bmp.user.services;

import com.bmp.user.dto.UserDtos.*;
import com.bmp.user.entities.OnboardingState;
import com.bmp.user.entities.UserRoles;
import com.bmp.user.entities.Users;
import com.bmp.user.repositories.OnboardingStateRepository;
import com.bmp.user.repositories.UserRolesRepository;
import com.bmp.user.repositories.UsersRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BMP-22 (+Session 13 completion pass): users / user_roles / onboarding_state.
 *
 * <p>Session 13 changes worth knowing:
 * <ul>
 *   <li>{@link #create} now stores users as VERIFIED. Every creation path that exists
 *       today goes through bmp-auth AFTER a successful OTP verification (including the
 *       Google flow, which still requires phone OTP because users.phone is NOT NULL) —
 *       storing them unverified, as before, meant is_verified was false forever for
 *       every user in the system. If a pre-verification creation path ever appears,
 *       add an explicit {@code verified} flag to CreateUserRequest then.</li>
 *   <li>Soft deactivation (deactivated_at, V004) — reversed automatically by bmp-auth
 *       on the user's next successful OTP login, Instagram-style. Deactivated users'
 *       profiles still resolve internally (services need them for old bookings/reviews);
 *       blocking LOGIN is bmp-auth's job, not a lookup-time concern here.</li>
 *   <li>Role grants are deduplicated (app-level check + V004's unique index as the
 *       race-proof backstop).</li>
 *   <li>onboarding_state finally has its lifecycle implemented per CONTEXT.md Module 1:
 *       replaced wholesale on save, deleted on completion, never a business-data table.</li>
 * </ul>
 */
@Service
public class UserService {

    private final UsersRepository users;
    private final UserRolesRepository roles;
    private final OnboardingStateRepository onboarding;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserService(UsersRepository users, UserRolesRepository roles, OnboardingStateRepository onboarding) {
        this.users = users;
        this.roles = roles;
        this.onboarding = onboarding;
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        if (users.existsByPhone(req.phone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS");
        }
        // isVerified=true: see class javadoc — every current creation path is post-OTP.
        Users u = new Users(req.phone(), req.name(), req.gender(),
                req.age() == null ? 0 : req.age(), req.email(), null, null, null,
                req.defaultRole(), true);
        try {
            u = users.saveAndFlush(u); // flush inside the try so V004's unique constraint surfaces here
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two concurrent signups for the same phone raced past existsByPhone —
            // V004's uk_users_phone catches the loser. Same outward behavior as the check.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS");
        }
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

    // ---- deactivation (Session 13) ----

    @Transactional
    public UserResponse deactivate(UUID id) {
        Users u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.isDeactivated()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_DEACTIVATED");
        }
        u.deactivate();
        return toResponse(u);
    }

    /** Called by bmp-auth (internal) when a deactivated user completes a fresh OTP login —
     * the login itself is the "I want my account back" signal, no separate flow needed. */
    @Transactional
    public UserResponse reactivate(UUID id) {
        Users u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        u.reactivate(); // idempotent — reactivating an active user is a no-op state-wise
        return toResponse(u);
    }

    // ---- roles ----

    @Transactional
    public RoleResponse addRole(UUID userId, CreateRoleRequest req) {
        users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        boolean duplicate = req.salonId() != null
                ? roles.existsByUserIdAndRoleAndSalonId(userId, req.role(), req.salonId())
                : roles.existsByUserIdAndRole(userId, req.role());
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROLE_ALREADY_GRANTED");
        }
        UserRoles r = new UserRoles(userId, req.role(), req.salonId());
        try {
            r = roles.saveAndFlush(r);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROLE_ALREADY_GRANTED"); // V004 index caught a race
        }
        return new RoleResponse(r.getId(), r.getUserId(), r.getRole(), r.getSalonId());
    }

    public List<RoleResponse> listRoles(UUID userId) {
        return roles.findByUserId(userId).stream()
                .map(r -> new RoleResponse(r.getId(), r.getUserId(), r.getRole(), r.getSalonId()))
                .toList();
    }

    /** Session 13: role revocation (e.g. a manager removed from a salon — bmp-salon's
     * staff flow is the real caller). Refuses to remove the role currently set as the
     * user's default — switch the default first, otherwise the next token mint would
     * claim a role the user no longer holds. */
    @Transactional
    public void removeRole(UUID userId, UUID roleId) {
        UserRoles r = roles.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND"));
        if (!r.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        }
        Users u = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.getDefaultRole().equals(r.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "CANNOT_REMOVE_DEFAULT_ROLE: switch the user's default role first");
        }
        roles.deleteById(roleId);
    }

    /** Session 13: the "stylist who also books as a customer" case — switch which held
     * role is minted into the JWT on the next login/refresh. Must actually hold it. */
    @Transactional
    public UserResponse setDefaultRole(UUID userId, DefaultRoleRequest req) {
        Users u = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        boolean holdsIt = u.getDefaultRole().equals(req.defaultRole())
                || roles.existsByUserIdAndRole(userId, req.defaultRole());
        if (!holdsIt) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ROLE_NOT_HELD: grant the role before making it the default");
        }
        u.setDefaultRole(req.defaultRole());
        u.touch();
        return toResponse(u);
    }

    // ---- onboarding state (Session 13 — crash-recovery blob, per CONTEXT.md Module 1) ----

    @Transactional
    public OnboardingStateResponse saveOnboardingState(UUID userId, OnboardingStateRequest req) {
        users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        String json = writeJson(req.state());
        OnboardingState s = onboarding.findByUserId(userId).orElse(null);
        if (s == null) {
            s = onboarding.save(new OnboardingState(userId, json));
        } else {
            s.replaceState(json); // wholesale replace, not merge — client re-saves the full blob each step
        }
        return new OnboardingStateResponse(userId, req.state(), s.getUpdatedAt());
    }

    public OnboardingStateResponse getOnboardingState(UUID userId) {
        OnboardingState s = onboarding.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_ONBOARDING_IN_PROGRESS"));
        return new OnboardingStateResponse(userId, readJson(s.getStateJson()), s.getUpdatedAt());
    }

    /** Called when onboarding completes — per the spec this table is transient, "deleted
     * when onboarding completes, not a business data table". Idempotent. */
    @Transactional
    public void clearOnboardingState(UUID userId) {
        onboarding.deleteByUserId(userId);
    }

    // ---- helpers ----

    private String writeJson(Map<String, Object> state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATE_JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private UserResponse toResponse(Users u) {
        return new UserResponse(u.getId(), u.getPhone(), u.getName(), u.getGender(), u.getAge(),
                u.getEmail(), u.getProfilePhotoUrl(), u.getHairType(), u.getHairLength(),
                u.getDefaultRole(), u.isVerified(), u.getDeactivatedAt(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
