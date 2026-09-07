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
    /** Session 56 — the per-customer booking count on the user detail view. */
    private final com.bmp.admin.client.BookingServiceClient bookings;

    /**
     * Session 65 — the authority matrix, for IRREVERSIBLE actions.
     *
     * <p>Account editing is governed by AccountScope (a permission per tier of account). Erasure is
     * governed by V010's matrix, which says something AccountScope cannot: support and support
     * managers are at ZERO for {@code user.anonymise} and must escalate to ops. Two rules, and the
     * one that was actually consulted was the permissive one — see removeAccount.
     */
    private final AuthorityService authority;

    public ConsoleUserService(UserServiceClient users, com.bmp.admin.client.AuthServiceClient auth,
                              PiiMasker masker, AuditLogService audit,
                              com.bmp.admin.client.BookingServiceClient bookings,
                              AuthorityService authority) {
        this.users = users;
        this.auth = auth;
        this.masker = masker;
        this.audit = audit;
        this.bookings = bookings;
        this.authority = authority;
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
        // ONE user, so the booking count is one extra call — see toSummary's note on why the
        // list path deliberately skips it.
        return toSummary(user, true);
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
    public void clearOtpLockout(UUID userId, String reason, StaffPrincipal caller) {
        var target = requireUser(userId);
        com.bmp.admin.security.AccountScope.requireCanManage(caller, target.defaultRole(), userId);
        requireReason(reason);

        auth.unlock(userId);

        audit.record("bmp_staff", caller.staffId(), "OTP_LOCKOUT_CLEARED", "user", userId,
                java.util.Map.of("targetRole", String.valueOf(target.defaultRole())),
                null, caller.email(), caller.role(), reason);

        log.warn("OTP lockout CLEARED by {} for {} ({}) — reason: {}",
                caller.email(), userId, target.defaultRole(), reason);
    }

    /**
     * Change a user's phone and/or email. Session 65.
     *
     * <h2>Changing the phone is a security event, not an edit</h2>
     * The phone IS the login identity. After this the old number cannot sign in and the new one
     * can — which is the whole point, and also precisely how an account is stolen if the request
     * was never verified. The authority check below decides whether this staff member may act on
     * this KIND of account; whether the person on the phone is who they claim is a judgement the
     * agent makes, and the required reason is what makes that judgement reviewable afterwards.
     */
    public void changeContact(UUID userId, String phone, String email, String reason,
                               StaffPrincipal caller) {
        var target = requireUser(userId);
        com.bmp.admin.security.AccountScope.requireCanManage(caller, target.defaultRole(), userId);
        requireReason(reason);

        users.changeContact(userId, new UserServiceClient.ChangeContactRequest(phone, email));

        /*
         * Old AND new in the audit entry.
         *
         * "Phone changed" is not reviewable. "Phone changed from X to Y by this agent, because the
         * customer called and said they had lost the number" is — and it is the only record that
         * survives if the change turns out to have been social engineering.
         */
        audit.record("bmp_staff", caller.staffId(), "ACCOUNT_CONTACT_CHANGED", "user", userId,
                java.util.Map.of(
                        "oldPhone", String.valueOf(target.phone()),
                        "newPhone", String.valueOf(phone),
                        "oldEmail", String.valueOf(target.email()),
                        "newEmail", String.valueOf(email),
                        "targetRole", String.valueOf(target.defaultRole())),
                null, caller.email(), caller.role(), reason);

        log.warn("Account contact CHANGED by {} for {} ({}) — reason: {}",
                caller.email(), userId, target.defaultRole(), reason);
    }

    /**
     * Block: reversible, and deliberately so. Bookings and history are untouched.
     *
     * <h2>Two calls, because a block has two halves</h2>
     * {@code users.block} stops the login door. {@code auth.revokeSessions} closes the sessions
     * that are open RIGHT NOW — without it, somebody already signed in carries on working until
     * their refresh token expires days later, which for the case a block is usually for is the
     * only window that mattered.
     *
     * <p>Order matters: block FIRST. If the revoke fails, the account is still blocked and the
     * sessions die at the next refresh. Revoking first and then failing to block would log the
     * person out and let them straight back in — the worst of both.
     *
     * <h2>This used to call deactivate(), and that is why it did nothing</h2>
     * bmp-auth reactivates a deactivated account on the owner's next OTP login. The button
     * worked, the audit entry was written, and the block lifted itself. See bmp-user V006.
     */
    public void blockAccount(UUID userId, String reason, StaffPrincipal caller) {
        var target = requireUser(userId);
        com.bmp.admin.security.AccountScope.requireCanManage(caller, target.defaultRole(), userId);
        requireReason(reason);

        users.block(userId, new UserServiceClient.BlockRequest(caller.staffId(), reason));

        try {
            auth.revokeSessions(userId);
        } catch (Exception e) {
            /*
             * Swallowed, and loudly. The block itself has committed — throwing now would report
             * failure for an action that succeeded, and an agent who believes the block failed
             * will try again and hit ALREADY_BLOCKED.
             *
             * The consequence of landing here is bounded: bmp-auth refuses the refresh anyway, so
             * the session ends at its next renewal instead of immediately.
             */
            log.error("Account {} was BLOCKED but its live sessions could not be revoked. The block "
                    + "holds; existing sessions will end at their next refresh instead of now.", userId, e);
        }

        audit.record("bmp_staff", caller.staffId(), "ACCOUNT_BLOCKED", "user", userId,
                java.util.Map.of("targetRole", String.valueOf(target.defaultRole())),
                null, caller.email(), caller.role(), reason);
        log.warn("Account BLOCKED by {}: {} ({}) — {}", caller.email(), userId, target.defaultRole(), reason);
    }

    /**
     * Lift a block.
     *
     * <p>Same authority as applying one: whoever can stop somebody must be able to un-stop them,
     * or a mistaken block becomes a permanent one that needs a more senior person to undo — and
     * agents faced with that stop blocking anybody, which is its own failure.
     *
     * <p>The reason is required here too. "Why was this lifted" is the question asked when the
     * account goes on to do the thing it was blocked for.
     */
    public void unblockAccount(UUID userId, String reason, StaffPrincipal caller) {
        var target = requireUser(userId);
        com.bmp.admin.security.AccountScope.requireCanManage(caller, target.defaultRole(), userId);
        requireReason(reason);

        users.unblock(userId);

        audit.record("bmp_staff", caller.staffId(), "ACCOUNT_UNBLOCKED", "user", userId,
                java.util.Map.of("targetRole", String.valueOf(target.defaultRole())),
                null, caller.email(), caller.role(), reason);
        log.warn("Account UNBLOCKED by {}: {} ({}) — {}", caller.email(), userId, target.defaultRole(), reason);
    }

    /**
     * Remove: ANONYMISE, not delete.
     *
     * <p>A hard delete would take the person's bookings with them, and those bookings are the
     * SALON's record of work it performed and was paid for. bmp-user's anonymise clears the
     * identifying fields and keeps the row — both the correct reading of a DPDP erasure and the
     * only version a salon can operate with.
     *
     * <p>Irreversible: bmp-user refuses to reactivate an anonymised row. Hence WARN, and hence the
     * reason being mandatory.
     */
    public void removeAccount(UUID userId, String reason, StaffPrincipal caller) {
        var target = requireUser(userId);
        com.bmp.admin.security.AccountScope.requireCanManage(caller, target.defaultRole(), userId);

        /*
         * ── TWO RULES DISAGREED, AND THE PERMISSIVE ONE WAS WINNING. Session 65, a real hole. ───
         *
         * AccountScope says "you may administer a CUSTOMER account" and a support agent holds
         * account:manage_customer, so this method let a support agent PERMANENTLY ANONYMISE a
         * customer. bmp-user refuses to reactivate an anonymised row: there is no undo.
         *
         * Meanwhile V010's authority matrix has said the opposite since Session 58:
         *
         *     user.anonymise   support_agent  0 → ops_admin
         *                      support_lead   0 → ops_admin
         *                      ops_admin      unbounded
         *
         * Zero with an approver means "may REQUEST, may never perform". Nothing consulted it here,
         * so the matrix was documentation and AccountScope was the enforcement — the classic shape
         * where two copies of one rule drift and the looser copy is the one that runs.
         *
         * Asking the matrix makes it the single source of truth for irreversible actions. Note the
         * order: AccountScope FIRST (may you touch this KIND of account at all), then the matrix
         * (may your role do this PARTICULAR irreversible thing). Both are required; neither alone
         * is the answer.
         *
         * Value 0 because erasure has no amount. The 0-vs-NULL ceiling distinction still routes
         * correctly — see AuthorityService.check.
         */
        AuthorityService.Decision erasure = authority.check("user.anonymise", caller.role(), 0L);
        if (!erasure.allowed()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    erasure.needsApproval()
                        ? "Removing an account is permanent and your role can't do it directly. "
                          + "Raise it with " + humanApprover(erasure.approverRole()) + "."
                        : erasure.reason());
        }

        requireReason(reason);

        users.anonymise(userId, reason);

        audit.record("bmp_staff", caller.staffId(), "ACCOUNT_REMOVED", "user", userId,
                java.util.Map.of("targetRole", String.valueOf(target.defaultRole())),
                null, caller.email(), caller.role(), reason);
        log.warn("Account REMOVED (anonymised) by {}: {} ({}) — {}",
                caller.email(), userId, target.defaultRole(), reason);
    }

    /** Role codes are for storage; a refusal a human reads should name a team. */
    private static String humanApprover(String role) {
        if (role == null) return "an ops admin";
        return switch (role) {
            case "support_lead" -> "a support manager";
            case "ops_admin" -> "an ops admin";
            case "admin" -> "an admin";
            case "super_admin" -> "the main admin";
            case "finance_admin" -> "finance";
            default -> role.replace('_', ' ');
        };
    }

    private UserServiceClient.UserDto requireUser(UUID userId) {
        var body = users.getUserById(userId).getBody();
        if (body == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "No such account.");
        }
        return body;
    }

    /**
     * A reason is required by the APPLICATION, not the schema.
     *
     * <p>The audit table accepts null because migrations and system actions write rows too. A human
     * blocking somebody's account is not one of those cases — and the reason is the only part of
     * the entry that explains the decision to whoever reads it six months later.
     */
    private void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Say why — this is written to the audit log and read back later.");
        }
    }

    public void unlock(UUID userId, JustifiedActionRequest req, StaffPrincipal caller, String ip) {
        requirePiiCapableRole(caller);

        /*
         * ── THE SAME EFFECT VIA TWO DOORS, AND THIS ONE WAS THE WEAKER. Session 65. ────────────
         *
         * `clearOtpLockout` and this method both end in `auth.unlock(userId)` — identical effect,
         * different names, different audit actions, and until now different authorisation:
         *
         *   clearOtpLockout   AccountScope.requireCanManage  — knows WHOSE account it is
         *   unlock            requirePiiCapableRole only     — knows only that you handle PII
         *
         * requirePiiCapableRole passes any support agent. AccountScope would refuse that same
         * agent on a SALON OWNER's or an ADMIN's account. So the weaker door let support unlock
         * accounts the stronger door exists to protect — and the weaker door is the one the console
         * actually calls, because clearOtpLockout was never wired to a button.
         *
         * This is the same shape as the erasure hole found earlier this session: two copies of one
         * decision, drifted, and the permissive copy is the one in the code path. Both doors now
         * ask the same question. They keep their separate audit actions on purpose — "OTP lockout
         * cleared" and "account unlocked" read differently in a log, and the distinction is real
         * even though the mechanism is not.
         */
        var target = requireUser(userId);
        com.bmp.admin.security.AccountScope.requireCanManage(caller, target.defaultRole(), userId);

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
        return toSummary(u, false);
    }

    /**
     * @param withBookingCount ask bmp-booking how many bookings this person has made.
     *
     * <h2>Why it is a parameter and not always on</h2>
     * This method renders both a LIST and a single user. On a list it is called once per row, and
     * a remote call per row is how a fifty-row page becomes fifty round trips — the reason the
     * original TODO concluded it was "not worth a second call per row". That reasoning was right
     * for the list and wrong for the detail view, where it is one call and real context: "this is
     * their eleventh booking" changes how an agent handles a complaint.
     *
     * <p>So: off for lists, on for one user.
     */
    private UserSummaryResponse toSummary(UserServiceClient.UserDto u, boolean withBookingCount) {
        Long bookingCount = null;
        if (withBookingCount) {
            try {
                var body = bookings.countByCustomer(u.id());
                bookingCount = body == null ? null : body.get("total");
            } catch (Exception e) {
                // Non-fatal, and deliberately so: an agent looking at a user during an incident
                // needs the screen, not the trivia. Null renders as "—" rather than an error.
                log.warn("Could not read the booking count for user {} ({}) — the profile is "
                        + "shown without it.", u.id(), e.toString());
            }
        }
        return new UserSummaryResponse(
                u.id(), u.name(), masker.phone(u.phone()), masker.email(u.email()),
                u.defaultRole(), u.isVerified(), u.deactivatedAt(), u.createdAt(),
                bookingCount);
    }
}
