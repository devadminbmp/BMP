package com.bmp.admin.services;

import com.bmp.admin.client.UserServiceClient;
import com.bmp.admin.dto.ConsoleDtos.*;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer lookup for the console.
 *
 * <h2>Search only — there is no "list all customers"</h2>
 * A console you can page through invites idle browsing of personal data, and idle browsing is
 * exactly what can't be defended afterwards. You look someone up because you're helping them,
 * and the audit log should read that way.
 *
 * <h2>Everything is masked until someone gives a reason</h2>
 * {@link #reveal} returns ONE field and writes an audit entry naming the staff member, the
 * customer, the field and the justification. Deliberately not a "show everything" toggle: the
 * narrower the request, the more meaningful the record.
 *
 * <p>The point isn't to make support's job harder — agents genuinely need customer phone
 * numbers, and it takes two seconds. It's that nobody sees one <em>by accident</em>, and every
 * time anybody does, there's a line with their name on it.
 */
@Service
public class ConsoleUserService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleUserService.class);

    private final UserServiceClient users;
    private final com.bmp.admin.client.AuthServiceClient auth;
    private final PiiMasker masker;
    private final AuditLogService audit;

    public ConsoleUserService(UserServiceClient users, com.bmp.admin.client.AuthServiceClient auth,
                              PiiMasker masker, AuditLogService audit) {
        this.users = users;
        this.auth = auth;
        this.masker = masker;
        this.audit = audit;
    }

    /**
     * Find a customer.
     *
     * <p>Only an exact E.164 phone lookup works today, because that's the only search bmp-user
     * exposes — and it exposes only that on purpose, since arbitrary name matching over a
     * customer table is an enumeration risk.
     *
     * <p>TODO(bmp-user): a staff-only search by name or partial phone, rate-limited and audited.
     * Until then an agent needs the customer's full number, which is usually how the
     * conversation starts anyway.
     */
    public List<UserSummaryResponse> search(String query, StaffPrincipal caller) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return List.of();

        if (!q.startsWith("+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search by full phone number in +91… form. Name search isn't available yet.");
        }

        try {
            UserServiceClient.UserDto user = users.getUserByPhone(q).getBody();
            if (user == null) return List.of();
            // The SEARCH itself is audited, not just reveals. Knowing who looked someone up is
            // often more useful than knowing who unmasked a field.
            audit.record("bmp_staff", caller.staffId(), "USER_SEARCHED", "user", user.id(),
                    Map.of(), null, caller.email(), caller.role(), null);
            return List.of(toSummary(user));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("User search failed for {} ({})", caller.email(), e.toString());
            return List.of();
        }
    }

    public UserSummaryResponse getById(UUID userId) {
        UserServiceClient.UserDto user = users.getUserById(userId).getBody();
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        return toSummary(user);
    }

    /**
     * Unmask one field, with a reason.
     *
     * <p>The justification is required by the DTO's validation AND recorded here. A reveal with
     * an empty reason is not possible through any path.
     */
    public RevealPiiResponse reveal(UUID userId, RevealPiiRequest req, StaffPrincipal caller, String ip) {
        if (!caller.can(StaffPermission.USER_PII_REVEAL)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role cannot reveal customer contact details.");
        }

        UserServiceClient.UserDto user = users.getUserById(userId).getBody();
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        String value = "phone".equals(req.field()) ? user.phone() : user.email();
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That field is empty on this account.");
        }

        audit.record("bmp_staff", caller.staffId(), "PII_REVEALED", "user", userId,
                Map.of("field", req.field()), ip, caller.email(), caller.role(), req.justification());

        log.info("PII revealed: field={} user={} by={} reason={}",
                req.field(), userId, caller.email(), req.justification());

        return new RevealPiiResponse(value);
    }

    /**
     * Why can't this person sign in?
     *
     * <p>Session 23: now real. Account facts come from bmp-user; lockout, failed attempts and
     * last-code-sent come from bmp-auth's internal OTP state.
     *
     * <p>If bmp-auth is unreachable the login-attempt fields come back EMPTY rather than
     * defaulted to "fine". An agent told "not locked" by a system that doesn't actually know
     * will confidently tell a customer the wrong thing, and the customer will keep trying a
     * locked account. Empty is honest; the console words it as "couldn't check".
     *
     * <p>Still missing: whether their email is bouncing. That needs delivery outcomes from
     * bmp-notification, which doesn't record them yet — TODO(bmp-notification). It reports
     * false, which means "no known failure", not "confirmed working".
     */
    public AccountHealthResponse accountHealth(UUID userId, StaffPrincipal caller) {
        UserServiceClient.UserDto user = users.getUserById(userId).getBody();
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        audit.record("bmp_staff", caller.staffId(), "ACCOUNT_HEALTH_VIEWED", "user", userId,
                Map.of(), null, caller.email(), caller.role(), null);

        com.bmp.admin.client.AuthServiceClient.OtpState otp = null;
        try {
            otp = auth.otpState(userId);
        } catch (Exception e) {
            log.warn("Could not read OTP state for {} ({}) — returning account facts only",
                    userId, e.toString());
        }

        return new AccountHealthResponse(
                user.id(), user.name(),
                masker.phone(user.phone()), masker.email(user.email()),
                user.defaultRole(), user.isVerified(), user.deactivatedAt(),
                otp == null ? null : otp.otpLockedUntil(),
                otp == null ? 0 : otp.failedAttempts(),
                otp == null ? null : otp.lastOtpSentAt(),
                // TODO(bmp-notification): real bounce state. False means "no known failure".
                false,
                null,
                false);
    }

    /**
     * Clear an OTP lockout.
     *
     * <p>Requires a justification, recorded against the agent's name. Unlocking on request is
     * precisely the step a social engineer wants an agent to take, so the reason they gave is
     * what makes it reviewable afterwards.
     *
     * <p>Deliberately does NOT send a code — that's a separate action, so an agent can help
     * someone genuinely locked out without triggering a login attempt the customer didn't ask
     * for.
     */
    public void unlock(UUID userId, JustifiedActionRequest req, StaffPrincipal caller, String ip) {
        requirePiiCapableRole(caller);
        try {
            auth.unlock(userId);
        } catch (Exception e) {
            log.error("Unlock failed for {} ({})", userId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Couldn't unlock that account just now — nothing was changed.");
        }
        audit.record("bmp_staff", caller.staffId(), "ACCOUNT_UNLOCKED", "user", userId,
                Map.of(), ip, caller.email(), caller.role(), req.justification());
        log.info("Account {} unlocked by {} — reason: {}", userId, caller.email(), req.justification());
    }

    /**
     * Re-send a login code to the address ALREADY on the account.
     *
     * <p>There is no destination parameter anywhere in this path — not here, not in bmp-auth.
     * Redirecting a login code is account takeover with extra steps, and the way to guarantee
     * it can't happen is for the capability not to exist.
     */
    public void resendLoginCode(UUID userId, JustifiedActionRequest req, StaffPrincipal caller, String ip) {
        requirePiiCapableRole(caller);
        try {
            auth.resendOtp(userId);
        } catch (feign.FeignException e) {
            if (e.status() == HttpStatus.CONFLICT.value()) {
                // No email on the account — codes are emailed today, so nothing can be
                // delivered. Say that rather than reporting a success nobody receives.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This account has no email address, and codes are delivered by email. "
                        + "They'll need to add one before they can sign in.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Couldn't send a code just now — please try again.");
        } catch (Exception e) {
            log.error("Resend failed for {} ({})", userId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Couldn't send a code just now — please try again.");
        }
        audit.record("bmp_staff", caller.staffId(), "LOGIN_CODE_RESENT", "user", userId,
                Map.of(), ip, caller.email(), caller.role(), req.justification());
        log.info("Login code re-sent for {} by {}", userId, caller.email());
    }

    private void requirePiiCapableRole(StaffPrincipal caller) {
        // Same permission that gates seeing a phone number: helping someone into their account
        // is at least as sensitive as reading their contact details.
        if (!caller.can(StaffPermission.USER_PII_REVEAL)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role cannot perform account recovery actions.");
        }
    }

    private UserSummaryResponse toSummary(UserServiceClient.UserDto u) {
        return new UserSummaryResponse(
                u.id(), u.name(), masker.phone(u.phone()), masker.email(u.email()),
                u.defaultRole(), u.isVerified(), u.deactivatedAt(), u.createdAt(),
                // TODO(bmp-booking): a per-customer booking count. Useful context for an agent
                // ("this is their eleventh booking"), not worth a second call per row yet.
                null);
    }
}
