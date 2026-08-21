package com.bmp.admin.services;

import com.bmp.admin.dto.AdminAuthDtos.*;
import com.bmp.admin.entities.BmpStaff;
import com.bmp.admin.entities.StaffActivation;
import com.bmp.admin.entities.StaffSession;
import com.bmp.admin.repositories.BmpStaffRepository;
import com.bmp.admin.repositories.StaffActivationRepository;
import com.bmp.admin.repositories.StaffSessionRepository;
import com.bmp.admin.security.AdminJwtService;
import com.bmp.admin.security.StaffBootstrap;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import com.bmp.admin.security.TotpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Console login: password, then TOTP, then a session.
 *
 * <h2>Rules this class exists to enforce</h2>
 * <ul>
 *   <li><b>Every failure looks identical.</b> Wrong email, wrong password, suspended account,
 *       never-activated account — all return the same 401 with the same wording. Distinguishing
 *       them tells an attacker which emails are real staff, which is free reconnaissance.</li>
 *   <li><b>2FA cannot be skipped.</b> A staff member with no TOTP secret is routed into
 *       enrolment, not into a session. Not by the account's creator, not by themselves.</li>
 *   <li><b>The challenge token is not a session token.</b> It carries a distinct stage claim and
 *       lives three minutes. Without that separation, "passed the password" would be
 *       indistinguishable from "logged in" — the bug that makes two-factor decorative.</li>
 *   <li><b>Lockout after repeated failures</b>, mirroring bmp-auth's OTP lockout so there's one
 *       mental model across the platform.</li>
 * </ul>
 */
@Service
public class StaffAuthService {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthService.class);

    private static final int MAX_FAILED_LOGINS = 5;
    private static final int LOCKOUT_MINUTES = 15;
    private static final int SESSION_DAYS = 7;
    private static final int ACTIVATION_TTL_HOURS = 48;
    private static final int MAX_ACTIVATION_ATTEMPTS = 5;

    /** The single message every authentication failure returns. See the class comment. */
    private static final String GENERIC_FAILURE = "Email or password is incorrect.";

    private final BmpStaffRepository staffRepo;
    private final StaffSessionRepository sessionRepo;
    private final StaffActivationRepository activationRepo;
    private final AdminJwtService jwt;
    private final TotpService totp;
    private final AuditLogService audit;
    private final BCryptPasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public StaffAuthService(BmpStaffRepository staffRepo, StaffSessionRepository sessionRepo,
                            StaffActivationRepository activationRepo, AdminJwtService jwt,
                            TotpService totp, AuditLogService audit, BCryptPasswordEncoder encoder) {
        this.staffRepo = staffRepo;
        this.sessionRepo = sessionRepo;
        this.activationRepo = activationRepo;
        this.jwt = jwt;
        this.totp = totp;
        this.audit = audit;
        this.encoder = encoder;
    }

    // ======================================================================================
    // Step 1 — password
    // ======================================================================================

    @Transactional
    public LoginResponse login(String email, String password, String ip) {
        Optional<BmpStaff> found = staffRepo.findByEmailIgnoreCase(email.trim());

        // Hash even when there's no such account, so a missing email and a wrong password take
        // the same time. Otherwise the response latency enumerates your staff list.
        if (found.isEmpty()) {
            encoder.matches(password, "$2a$12$0000000000000000000000000000000000000000000000000000");
            log.info("Console login failed (no such account) email={} ip={}", email, ip);
            throw unauthorized();
        }

        BmpStaff staff = found.get();

        if (staff.isLocked()) {
            log.warn("Console login attempted on LOCKED account {} ip={}", staff.getEmail(), ip);
            throw unauthorized();
        }
        // 'invited' means the activation code was never redeemed; 'suspended'/'offboarded'
        // speak for themselves. All indistinguishable from a wrong password, on purpose.
        if (!staff.isActive()) {
            log.info("Console login on non-active account {} (status={}) ip={}", staff.getEmail(), staff.getStatus(), ip);
            throw unauthorized();
        }
        // The bootstrap sentinel is not a bcrypt hash, so matches() would fail anyway — this
        // just makes the intent explicit and skips a pointless hash.
        if (StaffBootstrap.LOCKED.equals(staff.getPasswordHash())
                || !encoder.matches(password, staff.getPasswordHash())) {
            registerFailure(staff, ip);
            throw unauthorized();
        }

        // Password correct — clear the counter, but DON'T issue a session yet.
        staff.setFailedLoginCount(0);
        staff.setLockedUntil(null);
        staff.touch();
        staffRepo.save(staff);

        if (!staff.isTotpEnrolled()) {
            // First login. Generate a secret but do NOT persist it until they prove they've
            // stored it — otherwise someone who abandons enrolment is locked out of an account
            // that now believes it has a second factor.
            String secret = totp.generateSecret();
            String challenge = jwt.issueChallengeToken(staff.getId(), staff.getEmail(), staff.getRole());
            pendingSecrets.put(staff.getId(), secret);
            log.info("Console login: {} must enrol 2FA", staff.getEmail());
            return LoginResponse.enrolment(challenge, totp.provisioningUri(secret, staff.getEmail()));
        }

        return LoginResponse.challenge(jwt.issueChallengeToken(staff.getId(), staff.getEmail(), staff.getRole()));
    }

    /**
     * Secrets awaiting confirmation, held in memory only.
     *
     * <p>In-memory means enrolment must complete on the same instance, which is fine for one
     * console with a handful of staff. TODO(scale): move to Redis or a short-lived table before
     * running more than one bmp-admin instance, or enrolment will fail intermittently behind a
     * load balancer — a genuinely confusing bug, so it's written down.
     */
    private final java.util.Map<UUID, String> pendingSecrets = new java.util.concurrent.ConcurrentHashMap<>();

    // ======================================================================================
    // Step 2 — the code
    // ======================================================================================

    @Transactional
    public LoginResponse verifyTotp(String challengeToken, String code, String ip, String userAgent) {
        StaffPrincipal principal = requireChallenge(challengeToken);
        BmpStaff staff = staffRepo.findById(principal.staffId()).orElseThrow(this::unauthorized);

        if (!staff.isActive() || staff.isLocked()) throw unauthorized();

        if (!totp.verify(staff.getTotpSecret(), code)) {
            registerFailure(staff, ip);
            log.info("Console 2FA failed for {} ip={}", staff.getEmail(), ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That code is not valid.");
        }

        return establishSession(staff, ip, userAgent);
    }

    /** First login: confirm the code, and only then persist the secret. */
    @Transactional
    public LoginResponse enrolTotp(String challengeToken, String code, String ip, String userAgent) {
        StaffPrincipal principal = requireChallenge(challengeToken);
        BmpStaff staff = staffRepo.findById(principal.staffId()).orElseThrow(this::unauthorized);

        String pending = pendingSecrets.get(staff.getId());
        if (pending == null) {
            // The challenge expired, or the instance restarted. Start again rather than
            // silently issuing a new secret they haven't scanned.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Enrolment expired — sign in again to restart two-factor setup.");
        }
        if (!totp.verify(pending, code)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That code is not valid. Check your authenticator app is on the right entry.");
        }

        staff.setTotpSecret(pending);
        staff.setTotpEnrolledAt(Instant.now());
        staff.touch();
        staffRepo.save(staff);
        pendingSecrets.remove(staff.getId());

        audit.record("bmp_staff", staff.getId(), "TOTP_ENROLLED", "bmp_staff", staff.getId(),
                java.util.Map.of(), ip, staff.getEmail(), staff.getRole(), null);
        log.info("Console 2FA enrolled for {}", staff.getEmail());

        return establishSession(staff, ip, userAgent);
    }

    // ======================================================================================
    // Activation — how an employee turns an invite into an account
    // ======================================================================================

    /**
     * Redeem a one-time code and set a password.
     *
     * <p>The account moves from 'invited' to 'active' here. TOTP is still not set up, so the
     * next login routes into enrolment — two deliberate steps, because asking someone to choose
     * a password AND wrangle an authenticator app on one screen is where people give up and
     * pick something memorable.
     */
    @Transactional
    public void activate(String activationCode, String newPassword, String ip) {
        String hash = sha256(activationCode.trim());
        StaffActivation activation = activationRepo.findByCodeHashAndConsumedAtIsNull(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That activation code is not valid or has already been used."));

        if (activation.getAttemptCount() >= MAX_ACTIVATION_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts on this code. Ask for a new one.");
        }
        activation.setAttemptCount(activation.getAttemptCount() + 1);

        if (!activation.isRedeemable()) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "That activation code has expired. Ask an admin to issue a new one.");
        }

        BmpStaff staff = staffRepo.findById(activation.getStaffId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND"));

        staff.setPasswordHash(encoder.encode(newPassword));
        staff.setStatus("active");
        staff.setFailedLoginCount(0);
        staff.setLockedUntil(null);
        staff.touch();
        staffRepo.save(staff);

        activation.setConsumedAt(Instant.now());
        activationRepo.save(activation);

        audit.record("bmp_staff", staff.getId(), "STAFF_ACTIVATED", "bmp_staff", staff.getId(),
                java.util.Map.of("purpose", activation.getPurpose()), ip, staff.getEmail(), staff.getRole(), null);
        log.info("Staff account activated: {}", staff.getEmail());
    }

    /** Issues a code and returns it ONCE — only the hash is stored. */
    @Transactional
    public String issueActivationCode(BmpStaff staff, String purpose, UUID issuedBy) {
        // Any outstanding codes die, so a re-issue can't leave two valid codes in circulation.
        activationRepo.findByStaffIdAndConsumedAtIsNull(staff.getId()).forEach(a -> {
            a.setConsumedAt(Instant.now());
            activationRepo.save(a);
        });

        String code = randomCode();
        activationRepo.save(new StaffActivation(
                staff.getId(), sha256(code), purpose,
                Instant.now().plus(ACTIVATION_TTL_HOURS, ChronoUnit.HOURS), issuedBy));
        return code;
    }

    // ======================================================================================
    // Sessions
    // ======================================================================================

    private LoginResponse establishSession(BmpStaff staff, String ip, String userAgent) {
        String selector = randomToken(12);
        String verifier = randomToken(24);

        sessionRepo.save(new StaffSession(
                staff.getId(), selector, sha256(verifier),
                Instant.now().plus(SESSION_DAYS, ChronoUnit.DAYS),
                ip, truncate(userAgent, 300)));

        staff.setLastLoginAt(Instant.now());
        staff.touch();
        staffRepo.save(staff);

        audit.record("bmp_staff", staff.getId(), "STAFF_LOGIN", "bmp_staff", staff.getId(),
                java.util.Map.of(), ip, staff.getEmail(), staff.getRole(), null);

        return LoginResponse.authenticated(
                jwt.issueAccessToken(staff.getId(), staff.getEmail(), staff.getRole()),
                selector + "." + verifier,
                jwt.accessTokenSeconds(),
                toProfile(staff));
    }

    @Transactional
    public LoginResponse refresh(String refreshToken, String ip, String userAgent) {
        int dot = refreshToken.indexOf('.');
        if (dot < 0) throw unauthorized();

        StaffSession session = sessionRepo
                .findBySelectorAndRevokedFalse(refreshToken.substring(0, dot))
                .orElseThrow(this::unauthorized);

        if (!session.isUsable() || !constantTimeEquals(session.getVerifierHash(), sha256(refreshToken.substring(dot + 1)))) {
            throw unauthorized();
        }

        BmpStaff staff = staffRepo.findById(session.getStaffId()).orElseThrow(this::unauthorized);
        // Re-checked on every refresh: suspending someone takes effect within one token
        // lifetime without anything else needing to know.
        if (!staff.isActive()) {
            sessionRepo.revokeAllForStaff(staff.getId());
            throw unauthorized();
        }

        session.setLastUsedAt(Instant.now());
        sessionRepo.save(session);

        return LoginResponse.authenticated(
                jwt.issueAccessToken(staff.getId(), staff.getEmail(), staff.getRole()),
                refreshToken,          // not rotated — revocation is server-side, so it adds nothing
                jwt.accessTokenSeconds(),
                toProfile(staff));
    }

    @Transactional
    public void logout(UUID staffId, String refreshToken) {
        if (refreshToken != null && refreshToken.contains(".")) {
            sessionRepo.findBySelectorAndRevokedFalse(refreshToken.substring(0, refreshToken.indexOf('.')))
                    .ifPresent(s -> {
                        s.setRevoked(true);
                        sessionRepo.save(s);
                    });
        } else {
            // No token supplied — end everything for this person rather than nothing.
            sessionRepo.revokeAllForStaff(staffId);
        }
    }

    public StaffProfile profileOf(UUID staffId) {
        return toProfile(staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown staff")));
    }

    public StaffProfile toProfile(BmpStaff staff) {
        return new StaffProfile(
                staff.getId(), staff.getName(), staff.getEmail(), staff.getRole(), staff.getStatus(),
                StaffPermission.permissionsFor(staff.getRole()), staff.isTotpEnrolled(), staff.getLastLoginAt());
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private StaffPrincipal requireChallenge(String token) {
        StaffPrincipal p = jwt.parse(token, AdminJwtService.STAGE_CHALLENGE);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Your sign-in attempt expired. Please start again.");
        }
        return p;
    }

    private void registerFailure(BmpStaff staff, String ip) {
        staff.setFailedLoginCount(staff.getFailedLoginCount() + 1);
        if (staff.getFailedLoginCount() >= MAX_FAILED_LOGINS) {
            staff.setLockedUntil(Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES));
            log.warn("Console account LOCKED after {} failures: {} ip={}", MAX_FAILED_LOGINS, staff.getEmail(), ip);
            audit.record("system", staff.getId(), "STAFF_LOCKED", "bmp_staff", staff.getId(),
                    java.util.Map.of("failedAttempts", staff.getFailedLoginCount()), ip,
                    staff.getEmail(), staff.getRole(), null);
        }
        staff.touch();
        staffRepo.save(staff);
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, GENERIC_FAILURE);
    }

    /** URL-safe random, used for session selectors/verifiers. */
    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * Activation codes are read aloud and retyped, so they avoid characters people confuse:
     * no 0/O, no 1/I/l. Grouped for legibility — BMP-4K7P-9X2M.
     */
    private String randomCode() {
        final String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("BMP-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
