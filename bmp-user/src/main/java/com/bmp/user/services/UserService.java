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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(UserService.class);

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

    /** Session 65 — by email, for the salon inviting a stylist it knows by address rather than phone. */
    public UserResponse getByEmail(String email) {
        String needle = email == null ? "" : email.trim();
        if (needle.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMAIL_REQUIRED");
        }
        return users.findByEmailIgnoreCase(needle).map(this::toResponse)
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

    /**
     * Change the phone and/or email on an account. Session 65.
     *
     * <h2>Why an admin needs this at all</h2>
     * "I've changed my number" is the commonest account request there is, and until now the only
     * answer was "make a new account" — which loses the person's bookings, wallet balance and
     * history, and leaves the old account holding a phone number somebody else will eventually be
     * issued.
     *
     * <h2>The phone is the login identity, so changing it is a security event</h2>
     * After this the OLD number can no longer sign in and the NEW one can. That is the point, and
     * it is also exactly how an account is stolen if the request is not verified. The authority
     * check lives in bmp-admin (AccountScope) and the audit entry is written there; this method is
     * the mechanism and assumes the decision has already been made by someone entitled to make it.
     *
     * <p>Canonicalised before the uniqueness check, or `9876543210` and `+919876543210` would be
     * two different numbers to the index and the same number to a person.
     *
     * @param newPhone null to leave unchanged.
     * @param newEmail null to leave unchanged.
     */
    @Transactional
    public UserResponse changeContact(UUID id, String newPhone, String newEmail) {
        Users u = users.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (newPhone != null && !newPhone.isBlank()) {
            String canonical = canonicalPhone(newPhone);
            if (!canonical.equals(u.getPhone())) {
                /*
                 * Checked here AND enforced by uk_users_phone. The check gives a readable message;
                 * the constraint is what actually guarantees it, including against a concurrent
                 * change that passes this check a millisecond before the other one commits.
                 */
                if (users.existsByPhone(canonical)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Another account already uses that number.");
                }
                u.changeLoginPhone(canonical);
            }
        }

        if (newEmail != null && !newEmail.isBlank()) {
            /*
             * No uniqueness check on email, deliberately — there is no constraint on it either.
             * Phone is the identity; an email may legitimately be shared (a family with one inbox
             * and separate numbers). See the identity-doctor notes.
             */
            u.changeContactEmail(newEmail.trim());
        }

        u.touch();
        return toResponse(users.save(u));
    }

    /**
     * One phone number, one spelling. Mirrors AuthService.canonicalPhone.
     *
     * <p>Duplicated rather than shared because bmp-user must not depend on bmp-auth. The honest fix
     * is to move it into bmp-common; until then, the two must be changed together, and this comment
     * is the only thing saying so.
     */
    static String canonicalPhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        if (digits.length() != 10 || digits.charAt(0) < '6' || digits.charAt(0) > '9') return raw;
        return "+91" + digits;
    }

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

    /**
     * Block an account. Session 65.
     *
     * <h2>Why this is not {@code deactivate()}</h2>
     * Block was originally wired to {@code deactivate()}, and it did nothing. bmp-auth reactivates
     * any deactivated account the moment its owner completes an OTP login — that behaviour was
     * written for people who deactivate THEMSELVES and is correct for them. Pointed at a blocked
     * person it means the block lifts itself the next time they sign in.
     *
     * <p>So a block writes its own column, which nothing reverses automatically.
     *
     * <h2>Re-blocking is refused, not ignored</h2>
     * A second block would overwrite the first one's reason and timestamp, losing why the account
     * was stopped and when — exactly the facts somebody needs when the person disputes it.
     */
    @Transactional
    public UserResponse block(UUID id, UUID staffId, String reason) {
        Users u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.isAnonymised()) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "ACCOUNT_ANONYMISED: this account was already removed; there is nothing to block.");
        }
        if (u.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ALREADY_BLOCKED: unblock first if you want to change the reason.");
        }
        u.block(staffId, reason);
        log.warn("Account BLOCKED: user={} by staff={} — {}", id, staffId, reason);
        return toResponse(users.save(u));
    }

    /**
     * Lift a block.
     *
     * <p>Does NOT touch {@code deactivatedAt}. Somebody who had deactivated themselves and was then
     * blocked should, on unblocking, go back to being self-deactivated — not be silently
     * reactivated into an account they had chosen to pause.
     */
    @Transactional
    public UserResponse unblock(UUID id) {
        Users u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (!u.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NOT_BLOCKED");
        }
        u.unblock();
        log.warn("Account UNBLOCKED: user={}", id);
        return toResponse(users.save(u));
    }

    /** Called by bmp-auth (internal) when a deactivated user completes a fresh OTP login —
     * the login itself is the "I want my account back" signal, no separate flow needed. */
    @Transactional
    public UserResponse reactivate(UUID id) {
        Users u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        /*
         * V005 (Session 56) — an ANONYMISED account must never come back.
         *
         * This is the single most important line in the erasure work, and it is here rather than
         * in the anonymise method because THIS is the path that would undo it. Deactivation is
         * reversible by design: bmp-auth calls this the moment a deactivated user completes an OTP
         * login, with no separate "restore my account" flow. Without this check, a person whose
         * data we had erased under a deletion request would have their account restored by simply
         * logging in — except the personal fields are gone, so they would get a nameless, emailless
         * account and we would have reversed a compliance action by accident.
         *
         * It cannot happen in practice either, because the phone is now a tombstone and no OTP can
         * reach it. Both together: the number cannot be used to log in, AND the restore refuses.
         */
        if (u.isAnonymised()) {
            log.warn("Refused to reactivate user {} — the account was anonymised at {} and is "
                    + "terminal.", id, u.getAnonymisedAt());
            throw new ResponseStatusException(HttpStatus.GONE,
                    "ACCOUNT_ANONYMISED: this account was permanently deleted at the owner's "
                    + "request and cannot be restored.");
        }

        /*
         * Session 65 — a BLOCKED account must not be reactivated either, for the same reason and by
         * the same argument as the anonymised check above: this method is the path that would undo
         * it. bmp-auth refuses a blocked account long before it reaches here, so in practice this
         * never fires — which is exactly why it belongs here. The check that matters is the one
         * still standing the day the caller upstream changes.
         *
         * Note it does NOT clear blockedAt. Reactivation is about deactivatedAt; lifting a block is
         * a staff decision with an audit entry, and it has its own method.
         */
        if (u.isBlocked()) {
            log.warn("Refused to reactivate user {} — the account is blocked (since {}).",
                    id, u.getBlockedAt());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ACCOUNT_BLOCKED: this account has been blocked and cannot be reactivated by "
                    + "logging in.");
        }

        u.reactivate(); // idempotent — reactivating an active user is a no-op state-wise
        return toResponse(u);
    }

    /**
     * Erase this person's personal data. Session 56, and the thing a deletion request actually
     * needs.
     *
     * <h2>What it replaces</h2>
     * bmp-admin was closing DPDP/GDPR deletion requests by calling {@link #deactivate}, and saying
     * so plainly in the recorded outcome: <i>"full anonymisation is not implemented — personal
     * fields remain on the user record"</i>. Every field stayed, and deactivation is reversed on
     * the next login, so a "deleted" account came back intact.
     *
     * <h2>Idempotent, and terminal</h2>
     * A retried compliance job must not fail on work it already completed, so erasing an
     * already-erased account returns quietly. There is no un-anonymise, by design — see
     * {@link #reactivate}.
     *
     * @param reason short code recorded on the row (e.g. {@code deletion_request}). The ERASED
     *               VALUES ARE NOT KEPT anywhere — storing "what we deleted" beside the deletion
     *               would defeat it. Only the fact and the date survive, which is what a regulator
     *               asks for.
     */
    @Transactional
    public UserResponse anonymise(UUID id, String reason) {
        Users u = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (u.isAnonymised()) {
            log.info("User {} was already anonymised at {} — nothing to do.", id, u.getAnonymisedAt());
            return toResponse(u);
        }

        u.anonymise(reason == null || reason.isBlank() ? "deletion_request" : reason.trim());
        users.save(u);

        // No personal data in the log line either. The id is the only thing that may be recorded.
        log.info("User {} anonymised (reason={}). Personal fields cleared; the id survives so past "
                + "bookings and invoices keep referencing something.", id, u.getAnonymisedReason());
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
            // Session 65 — was silent. An empty map here reads downstream as "no data", which is a
            // different fact from "the data would not parse". Degrades as before; says so now.
            log.warn("Could not read stored JSON — treating it as empty ({})", e.toString());
            return Map.of();
        }
    }

    private UserResponse toResponse(Users u) {
        return new UserResponse(u.getId(), u.getPhone(), u.getName(), u.getGender(), u.getAge(),
                u.getEmail(), u.getProfilePhotoUrl(), u.getHairType(), u.getHairLength(),
                u.getDefaultRole(), u.isVerified(), u.getDeactivatedAt(), u.getCreatedAt(), u.getUpdatedAt(),
                u.getBlockedAt(), u.getBlockedReason());
    }
}
